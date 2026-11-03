package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.LabOpenSlot;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UsageRecordMapper;
import com.example.reservation.service.LabOpenSlotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约核心逻辑单元测试（L1 修复：审查报告指出"零自动化测试"，先补齐最核心的冲突/审核/取消规则回归集）。
 * 覆盖软件审查修复项：
 *  - H1 批量审核批内互斥（同批重叠整批拒绝）
 *  - H2 审核通过路径执行复审加锁读且无冲突时正常通过
 *  - H3 可预约时段窗口后端强制（08:00-22:00 + 单次时长上限）
 *  - M1 状态流转条件更新（并发状态变更后写者拒绝，不覆盖前写）
 *
 * @author reservation-team
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    /** V4.0：取消预约后级联清除该预约的使用登记，避免已取消预约仍计入实际使用统计 */
    @Mock
    private UsageRecordMapper usageRecordMapper;

    @Mock
    private RedisCache redisCache;

    /** 转换器外移后新增依赖：现有 6 条用例均不经过转换方法，mock 空实现即可 */
    @Mock
    private ReservationConverter reservationConverter;

    /** V4.0 规则 2/3 新增依赖：开放时段与容量上限（默认由各用例按需打桩） */
    @Mock
    private LabOpenSlotService labOpenSlotService;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    /**
     * 纯 Mockito 环境不经过 Spring 启动，MyBatis-Plus 的 Lambda 元数据（Reservation::getXxx 解析）
     * 需手动初始化，否则构造 LambdaQueryWrapper/LambdaUpdateWrapper 即抛
     * "can not find lambda cache for this entity"
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
    }

    @BeforeEach
    void setUp() {
        // 模拟登录态（学生 1 号）
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ===== H3：可预约时段窗口后端强制（08:00-22:00 + 单次时长上限） ===== */

    @Test
    @DisplayName("H3：开始时间早于 08:00 被拒绝")
    void createReservation_rejectsStartBeforeWindow() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "06:00", "08:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("08:00"));
    }

    @Test
    @DisplayName("H3：结束时间晚于 22:00 被拒绝")
    void createReservation_rejectsEndAfterWindow() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "21:00", "23:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("22:00"));
    }

    @Test
    @DisplayName("H3：单次时长超过 8 小时上限被拒绝")
    void createReservation_rejectsTooLongDuration() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "08:00", "21:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("8 小时"));
    }

    @Test
    @DisplayName("多角色：教师单次可约整个开放窗口（13 小时），不受学生 8 小时上限约束")
    void createReservation_teacherCanExceedStudentHourLimit() {
        stubEnabledRoom();
        UserContext.set(99L, "teacher", Constants.ROLE_TEACHER);
        try {
            ReservationDTO dto = dto("2026-09-20", "08:00", "21:00");
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> reservationService.createReservation(dto));
            // 13 小时未超过教师上限 14 小时，应越过时长校验进入后续环节（此处因时段/Mock 抛其他错误或正常返回）
            assertFalse(ex.getMessage().contains("小时") && ex.getMessage().contains("不能超过"));
        } finally {
            UserContext.clear();
        }
    }

    /* ===== H1：批量审核批内互斥（同批重叠必须整批拒绝） ===== */

    @Test
    @DisplayName("H1：同批两条时段重叠的待审核记录被整批拒绝")
    void batchAudit_rejectsOverlapWithinBatch() {
        Reservation r1 = reservation(101L, LocalTime.of(9, 0), LocalTime.of(11, 0));
        Reservation r2 = reservation(102L, LocalTime.of(10, 0), LocalTime.of(12, 0));
        when(reservationMapper.selectBatchIds(any())).thenReturn(List.of(r1, r2));
        stubCapacityLimit();

        BatchAuditDTO dto = new BatchAuditDTO();
        dto.setIds(List.of(101L, 102L));
        dto.setStatus(Constants.RES_STATUS_APPROVED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.batchAudit(dto));
        assertTrue(ex.getMessage().contains("时段重叠"));
        // 错误信息应点名冲突双方 ID，便于管理员单独处理
        assertTrue(ex.getMessage().contains("101"));
    }

    /* ===== H2：审核通过路径执行复审（加锁读已通过列表）且无冲突时正常通过 ===== */

    @Test
    @DisplayName("H2：审核通过无冲突时完成条件更新")
    void auditReservation_approveWhenNoConflict() {
        Reservation r = reservation(1L, LocalTime.of(9, 0), LocalTime.of(11, 0));
        when(reservationMapper.selectById(1L)).thenReturn(r);
        when(classroomMapper.selectOne(any())).thenReturn(new Classroom());
        // 无已通过记录 → 复审无冲突
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(reservationMapper.update(any(), any())).thenReturn(1);
        stubCapacityLimit();

        AuditDTO dto = new AuditDTO();
        dto.setStatus(Constants.RES_STATUS_APPROVED);

        reservationService.auditReservation(1L, dto);

        // 复审加锁读已执行（FOR UPDATE 查询已通过列表）+ 容量复查（规则 2）各读一次 + 条件更新已执行
        verify(reservationMapper, times(2)).selectList(any());
        verify(reservationMapper).update(any(), any());
    }

    /* ===== M1：条件更新 + 影响行数校验（并发状态变更后写者不覆盖） ===== */

    @Test
    @DisplayName("M1：取消时状态已被并发变更（影响 0 行）则拒绝并提示刷新")
    void cancelReservation_rejectsWhenStatusChanged() {
        Reservation r = reservation(1L, LocalTime.of(10, 0), LocalTime.of(12, 0));
        r.setReserveDate(LocalDate.now().plusDays(1));
        when(reservationMapper.selectById(1L)).thenReturn(r);
        // 模拟并发：条件更新影响 0 行（状态已被管理员审核/学生先取消）
        when(reservationMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.cancelReservation(1L, "临时有事"));
        assertTrue(ex.getMessage().contains("状态已变更"));
    }

    /* ===== 补充边界：过去日期 / 今天已过时刻 / 取消时限临界（R12 边界补全） ===== */

    @Test
    @DisplayName("边界：预约过去日期被拒绝（早于今天）")
    void createReservation_rejectsPastDate() {
        stubEnabledRoom();
        // 昨天：与当前时刻无关，恒为过去日期
        ReservationDTO dto = dto(LocalDate.now().minusDays(1).toString(), "10:00", "12:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("不能早于今天"));
    }

    @Test
    @DisplayName("边界：预约今天已过去的时刻被拒绝（M12）")
    void createReservation_rejectsTodayPastTime() {
        // 「开始时间需 ≥ 08:00（窗口内）且 ≤ 当前时刻」，仅当当前时刻晚于 08:00 时可构造
        LocalTime now = LocalTime.now();
        Assumptions.assumeTrue(now.isAfter(LocalTime.of(8, 0)),
                "当前时刻未晚于 08:00，无法构造「今天已过时刻」");
        stubEnabledRoom();
        ReservationDTO dto = dto(LocalDate.now().toString(), "08:00", "09:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("必须晚于当前时间"));
    }

    @Test
    @DisplayName("边界：取消时限——开始前 59 分钟禁止取消")
    void cancelReservation_rejectsWithinOneHour() {
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(59);
        Assumptions.assumeTrue(startAt.toLocalDate().equals(LocalDate.now()), "接近午夜，跳过以免跨天");
        LocalTime start = startAt.toLocalTime();
        Reservation r = reservation(1L, start, start.plusMinutes(60));
        r.setReserveDate(LocalDate.now());
        when(reservationMapper.selectById(1L)).thenReturn(r);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.cancelReservation(1L, "临时有事"));
        assertTrue(ex.getMessage().contains("禁止取消"));
    }

    @Test
    @DisplayName("边界：取消时限——开始前 61 分钟（刚过 1 小时）可取消")
    void cancelReservation_allowsJustOverOneHour() {
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(61);
        Assumptions.assumeTrue(startAt.toLocalDate().equals(LocalDate.now()), "接近午夜，跳过以免跨天");
        LocalTime start = startAt.toLocalTime();
        Reservation r = reservation(1L, start, start.plusMinutes(60));
        r.setReserveDate(LocalDate.now());
        when(reservationMapper.selectById(1L)).thenReturn(r);
        when(reservationMapper.update(any(), any())).thenReturn(1);

        reservationService.cancelReservation(1L, "临时有事");

        // 通过时限校验后执行条件更新 → 取消成功
        verify(reservationMapper).update(any(), any());
        // 取消成功后级联删除使用登记（无登记时 delete 影响 0 行，同样必须调用）
        verify(usageRecordMapper).delete(any());
    }

    /* ===== 规则 2：容量控制按开放时段累计（V4.0 修复：原实现只按时间重叠累计导致空转） ===== */

    @Test
    @DisplayName("规则2：同一开放时段内时间不重叠的已通过预约仍占用容量")
    void createReservation_countsCapacityAcrossSameSlot() {
        stubEnabledRoom();
        // 时段窗口 08:00-12:00，上限 60 人
        LabOpenSlot window = slot(LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(labOpenSlotService.listEffectiveSlotEntities(any(), any())).thenReturn(List.of(window));
        when(labOpenSlotService.resolveCapacityLimit(any(), any(), any(), any())).thenReturn(60);
        // 已通过 35 人 08:00-10:00（与本次 10:00-12:00 不重叠，但同属该开放时段）
        Reservation approved = reservation(9L, LocalTime.of(8, 0), LocalTime.of(10, 0));
        approved.setStatus(Constants.RES_STATUS_APPROVED);
        approved.setPeopleCount(35);
        when(reservationMapper.selectList(any())).thenReturn(List.of(approved));

        ReservationDTO dto = dto("2099-09-20", "10:00", "12:00");
        dto.setPeopleCount(30);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        // 35 + 30 > 60 → 必须被容量拦截，而不是放行
        assertTrue(ex.getMessage().contains("上限"));
        assertTrue(ex.getMessage().contains("60"));
    }

    @Test
    @DisplayName("规则2：同一开放时段内人数合计未超上限时放行")
    void createReservation_allowsWhenCapacitySufficient() {
        stubEnabledRoom();
        LabOpenSlot window = slot(LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(labOpenSlotService.listEffectiveSlotEntities(any(), any())).thenReturn(List.of(window));
        when(labOpenSlotService.resolveCapacityLimit(any(), any(), any(), any())).thenReturn(60);
        Reservation approved = reservation(9L, LocalTime.of(8, 0), LocalTime.of(10, 0));
        approved.setStatus(Constants.RES_STATUS_APPROVED);
        approved.setPeopleCount(25);
        when(reservationMapper.selectList(any())).thenReturn(List.of(approved));
        when(reservationMapper.insert(any(Reservation.class))).thenReturn(1);

        ReservationDTO dto = dto("2099-09-20", "10:00", "12:00");
        dto.setPeopleCount(30);

        // 25 + 30 = 55 ≤ 60：不得抛异常
        reservationService.createReservation(dto);
        verify(reservationMapper).insert(any(Reservation.class));
    }

    @Test
    @DisplayName("规则2：不同开放时段的已通过预约不占用本时段容量")
    void createReservation_ignoresOtherSlotOccupancy() {
        stubEnabledRoom();
        // 本次落在 14:00-18:00 时段，上限 30
        LabOpenSlot window = slot(LocalTime.of(14, 0), LocalTime.of(18, 0));
        when(labOpenSlotService.listEffectiveSlotEntities(any(), any())).thenReturn(List.of(window));
        when(labOpenSlotService.resolveCapacityLimit(any(), any(), any(), any())).thenReturn(30);
        // 上午时段的已通过 28 人，不应计入下午容量
        Reservation morning = reservation(9L, LocalTime.of(8, 0), LocalTime.of(12, 0));
        morning.setStatus(Constants.RES_STATUS_APPROVED);
        morning.setPeopleCount(28);
        when(reservationMapper.selectList(any())).thenReturn(List.of(morning));
        when(reservationMapper.insert(any(Reservation.class))).thenReturn(1);

        ReservationDTO dto = dto("2099-09-20", "14:00", "16:00");
        dto.setPeopleCount(30);

        reservationService.createReservation(dto);
        verify(reservationMapper).insert(any(Reservation.class));
    }

    /* ===== 测试数据构造 ===== */

    /** 构造一个开放时段窗口实体（仅需 openTime/closeTime，容量校验按窗口重叠判定） */
    private LabOpenSlot slot(LocalTime open, LocalTime close) {
        LabOpenSlot s = new LabOpenSlot();
        s.setOpenTime(open);
        s.setCloseTime(close);
        return s;
    }

    /** 预置一间启用中的教室（createReservation 先锁教室行再校验时段，必须 stub 教室查询） */
    private void stubEnabledRoom() {
        Classroom room = new Classroom();
        room.setId(1L);
        room.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        when(classroomMapper.selectOne(any())).thenReturn(room);
        // V4.0 规则 3：提交前需命中开放时段，此处放行（时段窗口本身的校验由 LabOpenSlotServiceImpl 单测覆盖）
        // 用 lenient：部分用例在时段窗口校验处就被拒绝，走不到开放时段/容量校验
        lenient().when(labOpenSlotService.requireEffectiveSlot(any(), any(), any(), any()))
                .thenReturn(new LabOpenSlot());
        stubCapacityLimit();
    }

    /** V4.0 规则 2：容量上限给足，不干扰冲突/窗口类用例的断言 */
    private void stubCapacityLimit() {
        lenient().when(labOpenSlotService.resolveCapacityLimit(any(), any(), any(), any())).thenReturn(999);
    }

    private ReservationDTO dto(String date, String start, String end) {
        ReservationDTO dto = new ReservationDTO();
        dto.setClassroomId(1L);
        dto.setReserveDate(date);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPurpose("自习");
        return dto;
    }

    private Reservation reservation(long id, LocalTime start, LocalTime end) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 9, 20));
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(Constants.RES_STATUS_PENDING);
        r.setUserId(1L);
        return r;
    }
}
