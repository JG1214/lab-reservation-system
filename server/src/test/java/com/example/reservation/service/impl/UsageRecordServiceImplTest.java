package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.dto.UsageRecordDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UsageRecord;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UsageRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用登记模块（需求设计文档 V4.0 模块五）单元测试。
 * 覆盖规则：只有已通过预约可登记、未开始的预约不可登记、同一预约不可重复登记、
 * 失约必须 0 人、正常使用不得超过预约人数、部分使用必须小于预约人数、批量标记失约跳过非已通过预约。
 *
 * @author reservation-team
 */
@ExtendWith(MockitoExtension.class)
class UsageRecordServiceImplTest {

    @Mock
    private UsageRecordMapper usageRecordMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private UsageRecordServiceImpl usageRecordService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), UsageRecord.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("已过去的已通过预约可正常登记，落库人数与状态与请求一致")
    void register_persistsRecordForFinishedReservation() {
        when(reservationMapper.selectById(9L)).thenReturn(approved(9L, 5, 1));
        when(usageRecordMapper.selectCount(any())).thenReturn(0L);

        UsageRecordDTO dto = new UsageRecordDTO();
        dto.setReservationId(9L);
        dto.setUseStatus(Constants.USE_STATUS_PARTIAL);
        dto.setActualPeople(3);
        dto.setCheckRemark("  设备检修占用部分座位  ");
        usageRecordService.register(dto);

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRecordMapper).insert(captor.capture());
        UsageRecord saved = captor.getValue();
        assertEquals(3, saved.getActualPeople());
        assertEquals(Constants.USE_STATUS_PARTIAL, saved.getUseStatus());
        assertEquals("设备检修占用部分座位", saved.getCheckRemark(), "备注应去除首尾空白");
        assertEquals(1L, saved.getRegistrarId());
        assertTrue(saved.getCheckTime() != null);
    }

    @Test
    @DisplayName("规则：未开始的预约不允许登记使用情况")
    void register_rejectsFutureReservation() {
        when(reservationMapper.selectById(3L)).thenReturn(approvedTomorrow(3L, 20));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(3L, Constants.USE_STATUS_NORMAL, 20)));
        assertTrue(ex.getMessage().contains("尚未开始"));
        verify(usageRecordMapper, never()).insert(any(UsageRecord.class));
    }

    @Test
    @DisplayName("规则：只有已通过的预约可以登记")
    void register_rejectsNonApprovedReservation() {
        Reservation r = approved(2L, 6, 1);
        r.setStatus(Constants.RES_STATUS_PENDING);
        when(reservationMapper.selectById(2L)).thenReturn(r);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(2L, Constants.USE_STATUS_NORMAL, 6)));
        assertTrue(ex.getMessage().contains("已通过"));
        verify(usageRecordMapper, never()).insert(any(UsageRecord.class));
    }

    @Test
    @DisplayName("规则：同一预约不可重复登记")
    void register_rejectsDuplicate() {
        when(reservationMapper.selectById(9L)).thenReturn(approved(9L, 5, 1));
        when(usageRecordMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(9L, Constants.USE_STATUS_NORMAL, 5)));
        assertTrue(ex.getMessage().contains("重复登记"));
        verify(usageRecordMapper, never()).insert(any(UsageRecord.class));
    }

    @Test
    @DisplayName("规则：标记失约时实际人数必须为 0")
    void register_rejectsNoShowWithPeople() {
        when(reservationMapper.selectById(9L)).thenReturn(approved(9L, 5, 1));
        when(usageRecordMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(9L, Constants.USE_STATUS_NO_SHOW, 2)));
        assertTrue(ex.getMessage().contains("必须为 0"));
        verify(usageRecordMapper, never()).insert(any(UsageRecord.class));
    }

    @Test
    @DisplayName("规则：正常使用人数不得超过预约人数")
    void register_rejectsOverPeopleCount() {
        when(reservationMapper.selectById(9L)).thenReturn(approved(9L, 5, 1));
        when(usageRecordMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(9L, Constants.USE_STATUS_NORMAL, 6)));
        assertTrue(ex.getMessage().contains("不能超过预约人数"));
    }

    @Test
    @DisplayName("规则：部分使用人数必须小于预约人数")
    void register_rejectsPartialEqualPeopleCount() {
        when(reservationMapper.selectById(9L)).thenReturn(approved(9L, 5, 1));
        when(usageRecordMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(9L, Constants.USE_STATUS_PARTIAL, 5)));
        assertTrue(ex.getMessage().contains("必须小于预约人数"));
    }

    @Test
    @DisplayName("规则：使用状态只接受 1/2/3")
    void register_rejectsIllegalUseStatus() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.register(dto(9L, 4, 1)));
        assertTrue(ex.getMessage().contains("使用状态"));
        verify(reservationMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("批量标记失约：非已通过、未开始与已登记的预约被跳过，只插入真正失约的预约")
    void batchNoShow_skipsIneligibleReservations() {
        Reservation finished = approved(9L, 5, 1);
        Reservation pending = approved(13L, 3, 0);
        pending.setStatus(Constants.RES_STATUS_PENDING);
        Reservation future = approvedTomorrow(14L, 3);
        // 批量路径一次性批量取预约（与单条登记的 selectById 不同），因此按 selectBatchIds 打桩
        when(reservationMapper.selectBatchIds(any())).thenReturn(Arrays.asList(finished, pending, future));
        when(usageRecordMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        int affected = usageRecordService.batchNoShow(Arrays.asList(9L, 13L, 14L), "  集体请假  ");

        assertEquals(1, affected);
        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRecordMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getActualPeople());
        assertEquals(Constants.USE_STATUS_NO_SHOW, captor.getValue().getUseStatus());
        assertEquals("集体请假", captor.getValue().getCheckRemark());
    }

    @Test
    @DisplayName("批量标记失约：全部不符合条件时抛出可读业务异常")
    void batchNoShow_throwsWhenNothingEligible() {
        Reservation pending = approved(13L, 3, 0);
        pending.setStatus(Constants.RES_STATUS_PENDING);
        when(reservationMapper.selectBatchIds(any())).thenReturn(java.util.List.of(pending));
        when(usageRecordMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> usageRecordService.batchNoShow(java.util.List.of(13L), null));
        assertTrue(ex.getMessage().contains("无需重复标记"));
        verify(usageRecordMapper, never()).insert(any(UsageRecord.class));
    }

    /* ===== 测试数据构造 ===== */

    /** 当天已结束的已通过预约 */
    private Reservation approved(Long id, int peopleCount, int ownerId) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setUserId((long) ownerId);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.now());
        r.setStartTime(LocalTime.of(0, 1));
        r.setEndTime(LocalTime.of(0, 31));
        r.setPeopleCount(peopleCount);
        r.setStatus(Constants.RES_STATUS_APPROVED);
        return r;
    }

    /** 明天才开始、尚未发生使用的已通过预约 */
    private Reservation approvedTomorrow(Long id, int peopleCount) {
        Reservation r = approved(id, peopleCount, 1);
        r.setReserveDate(LocalDate.now().plusDays(1));
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(11, 0));
        return r;
    }

    private UsageRecordDTO dto(Long reservationId, Integer useStatus, Integer actualPeople) {
        UsageRecordDTO dto = new UsageRecordDTO();
        dto.setReservationId(reservationId);
        dto.setUseStatus(useStatus);
        dto.setActualPeople(actualPeople);
        return dto;
    }
}
