package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.example.reservation.service.UsageRecordService;
import com.example.reservation.vo.UsageRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用登记服务实现（需求设计文档 V4.0 2.3.4 表 7 usage_record + 规则 5 失约建模）
 *
 * <p>核心约束（与《接口定义清单-V4.0》第 5 节一一对应）：
 * <ol>
 *   <li>只有「已通过(1)」的预约可以登记，待审核/已驳回/已取消不产生使用记录；</li>
 *   <li>同一预约只能登记一次 —— 先查后插 + uk_reservation 唯一索引双保险，
 *       并发下捕获 DuplicateKeyException 转成业务提示；</li>
 *   <li>useStatus=3（失约）时 actualPeople 必须为 0；useStatus≠3 时 1 ≤ actualPeople ≤ peopleCount；
 *       useStatus=2（部分使用）时 actualPeople 严格小于 peopleCount；</li>
 *   <li>失约不改变 reservation.status，预约仍为「已通过」，因此冲突检测口径无需改动（负责人已确认 A2）。</li>
 * </ol>
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class UsageRecordServiceImpl implements UsageRecordService {

    /** 使用状态中文标签 */
    private static final Map<Integer, String> USE_STATUS_LABELS = Map.of(
            Constants.USE_STATUS_NORMAL, "正常使用",
            Constants.USE_STATUS_PARTIAL, "部分使用",
            Constants.USE_STATUS_NO_SHOW, "失约");

    private static final String NO_SHOW_DEFAULT_REMARK = "管理员批量标记失约";

    /** 待登记列表默认回溯天数：覆盖近两周未登记的历史预约，避免漏登 */
    private static final int PENDING_LOOKBACK_DAYS = 14;

    /** 使用记录查询跨度上限（与看板 366 天口径一致） */
    private static final int MAX_RANGE_DAYS = 366;

    @Resource
    private UsageRecordMapper usageRecordMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Override
    public List<UsageRecordVO> listPending(String date) {
        LocalDate target = parseDate(date, LocalDate.now());
        // 已登记过的预约直接排除，避免重复出现在待登记列表
        Set<Long> registered = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                        .select(UsageRecord::getReservationId))
                .stream().map(UsageRecord::getReservationId).collect(Collectors.toSet());

        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                .le(Reservation::getReserveDate, target)
                .ge(Reservation::getReserveDate, target.minusDays(PENDING_LOOKBACK_DAYS))
                .orderByAsc(Reservation::getReserveDate)
                .orderByAsc(Reservation::getStartTime));

        LocalDateTime now = LocalDateTime.now();
        List<Reservation> pending = approved.stream()
                .filter(r -> !registered.contains(r.getId()))
                // 今日尚未结束的预约还不是「待登记」，先不催登记
                .filter(r -> !r.getReserveDate().isAfter(target))
                .filter(r -> r.getReserveDate().isBefore(now.toLocalDate())
                        || !r.getEndTime().isAfter(now.toLocalTime()))
                .toList();
        return toVOList(pending);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(UsageRecordDTO dto) {
        if (dto == null || dto.getReservationId() == null) {
            throw new BusinessException("请选择要登记的预约");
        }
        Integer useStatus = dto.getUseStatus();
        if (useStatus == null || !USE_STATUS_LABELS.containsKey(useStatus)) {
            throw new BusinessException("使用状态只能是 1-正常使用、2-部分使用、3-失约");
        }
        if (dto.getCheckRemark() != null && dto.getCheckRemark().length() > Constants.CHECK_REMARK_MAX_LENGTH) {
            throw new BusinessException("登记备注不能超过 " + Constants.CHECK_REMARK_MAX_LENGTH + " 个字符");
        }

        Reservation reservation = reservationMapper.selectById(dto.getReservationId());
        if (reservation == null) {
            throw new BusinessException("预约不存在");
        }
        if (reservation.getStatus() != Constants.RES_STATUS_APPROVED) {
            throw new BusinessException("只有已通过的预约可以登记使用情况");
        }
        Long exists = usageRecordMapper.selectCount(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getReservationId, reservation.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException("该预约已登记过使用记录，不能重复登记");
        }
        // 使用登记是对「已发生」的使用行为的记录，未开始的预约不允许提前登记
        if (LocalDateTime.of(reservation.getReserveDate(), reservation.getStartTime()).isAfter(LocalDateTime.now())) {
            throw new BusinessException("该预约尚未开始，请在预约结束后登记实际使用情况");
        }

        int peopleCount = reservation.getPeopleCount() == null ? 1 : reservation.getPeopleCount();
        int actualPeople = dto.getActualPeople() == null ? 0 : dto.getActualPeople();
        if (useStatus == Constants.USE_STATUS_NO_SHOW) {
            // 失约 = 完全未使用，实际人数必须为 0，否则语义矛盾
            if (actualPeople != 0) {
                throw new BusinessException("标记失约时实际使用人数必须为 0");
            }
        } else {
            if (actualPeople < 1) {
                throw new BusinessException("实际使用人数至少为 1 人，未到场请标记失约");
            }
            if (actualPeople > peopleCount) {
                throw new BusinessException("实际使用人数不能超过预约人数（" + peopleCount + " 人）");
            }
            if (useStatus == Constants.USE_STATUS_PARTIAL && actualPeople >= peopleCount) {
                throw new BusinessException("部分使用的人数必须小于预约人数（" + peopleCount + " 人），全员到场请选择正常使用");
            }
        }

        UsageRecord record = new UsageRecord();
        record.setReservationId(reservation.getId());
        record.setClassroomId(reservation.getClassroomId());
        record.setUserId(reservation.getUserId());
        record.setUseDate(reservation.getReserveDate());
        record.setActualPeople(actualPeople);
        record.setUseStatus(useStatus);
        record.setCheckRemark(trimToNull(dto.getCheckRemark()));
        record.setRegistrarId(UserContext.getUserId());
        record.setCheckTime(LocalDateTime.now());
        try {
            usageRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发双提交时由 uk_reservation 兜底，转为可读提示而非 500
            log.warn("使用登记并发冲突：reservationId={}", reservation.getId());
            throw new BusinessException("该预约已登记过使用记录，不能重复登记");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchNoShow(List<Long> reservationIds, String remark) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            throw new BusinessException("请选择要标记失约的预约");
        }
        String finalRemark = trimToNull(remark);
        if (finalRemark == null) {
            finalRemark = NO_SHOW_DEFAULT_REMARK;
        }
        if (finalRemark.length() > Constants.CHECK_REMARK_MAX_LENGTH) {
            throw new BusinessException("登记备注不能超过 " + Constants.CHECK_REMARK_MAX_LENGTH + " 个字符");
        }

        Set<Long> registered = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                        .select(UsageRecord::getReservationId)
                        .in(UsageRecord::getReservationId, reservationIds))
                .stream().map(UsageRecord::getReservationId).collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        // 与单条登记同一口径：已通过 + 未登记过 + 已经开始（未开始的预约不可能已失约）
        List<Reservation> targets = reservationMapper.selectBatchIds(reservationIds).stream()
                .filter(r -> r.getStatus() == Constants.RES_STATUS_APPROVED)
                .filter(r -> !registered.contains(r.getId()))
                .filter(r -> !LocalDateTime.of(r.getReserveDate(), r.getStartTime()).isAfter(now))
                .toList();
        if (targets.isEmpty()) {
            throw new BusinessException("所选预约均已登记、未开始或不是已通过状态，无需重复标记");
        }

        Long registrarId = UserContext.getUserId();
        for (Reservation r : targets) {
            UsageRecord record = new UsageRecord();
            record.setReservationId(r.getId());
            record.setClassroomId(r.getClassroomId());
            record.setUserId(r.getUserId());
            record.setUseDate(r.getReserveDate());
            record.setActualPeople(0);
            record.setUseStatus(Constants.USE_STATUS_NO_SHOW);
            record.setCheckRemark(finalRemark);
            record.setRegistrarId(registrarId);
            record.setCheckTime(now);
            usageRecordMapper.insert(record);
        }
        return targets.size();
    }

    @Override
    public List<UsageRecordVO> listRecords(String startDate, String endDate, Integer useStatus, Long classroomId) {
        if (useStatus != null && !USE_STATUS_LABELS.containsKey(useStatus)) {
            throw new BusinessException("使用状态只能是 1-正常使用、2-部分使用、3-失约");
        }
        LocalDate[] range = resolveRange(startDate, endDate);
        List<UsageRecord> records = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .ge(UsageRecord::getUseDate, range[0])
                .le(UsageRecord::getUseDate, range[1])
                .eq(useStatus != null, UsageRecord::getUseStatus, useStatus)
                .eq(classroomId != null, UsageRecord::getClassroomId, classroomId)
                .orderByDesc(UsageRecord::getUseDate)
                .orderByDesc(UsageRecord::getCheckTime));
        return buildVOs(records);
    }

    @Override
    public List<UsageRecordVO> listExport(String startDate, String endDate, Integer useStatus, Long classroomId) {
        // 导出口径与查询完全一致，复用同一实现，避免两处筛选条件漂移
        return listRecords(startDate, endDate, useStatus, classroomId);
    }

    /**
     * 待登记列表：预约 → VO（无登记信息）
     */
    private List<UsageRecordVO> toVOList(List<Reservation> list) {
        return buildVOs(Collections.emptyList(), list);
    }

    /**
     * 使用记录 → VO（登记信息 + 预约原文）
     */
    private List<UsageRecordVO> buildVOs(List<UsageRecord> records) {
        if (records.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> reservationIds = records.stream().map(UsageRecord::getReservationId).toList();
        Map<Long, Reservation> reservationMap = reservationMapper.selectBatchIds(reservationIds).stream()
                .collect(Collectors.toMap(Reservation::getId, Function.identity(), (a, b) -> a));
        return buildVOs(records, new ArrayList<>(reservationMap.values()));
    }

    /**
     * 统一组装：records 可为空（待登记列表无登记信息）
     */
    private List<UsageRecordVO> buildVOs(List<UsageRecord> records, List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, UsageRecord> recordByReservation = records.stream()
                .collect(Collectors.toMap(UsageRecord::getReservationId, Function.identity(), (a, b) -> a));

        Set<Long> classroomIds = reservations.stream().map(Reservation::getClassroomId).collect(Collectors.toSet());
        Map<Long, Classroom> classroomMap = classroomMapper.selectBatchIds(classroomIds).stream()
                .collect(Collectors.toMap(Classroom::getId, Function.identity(), (a, b) -> a));

        Set<Long> userIds = reservations.stream().map(Reservation::getUserId).collect(Collectors.toSet());
        records.forEach(r -> userIds.add(r.getRegistrarId() == null ? -1L : r.getRegistrarId()));
        // 登记人 ID 一并查出，避免列表里只显示数字 ID
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMap = sysUserMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
        }

        List<UsageRecordVO> result = new ArrayList<>(reservations.size());
        for (Reservation r : reservations) {
            UsageRecord record = recordByReservation.get(r.getId());
            UsageRecordVO vo = new UsageRecordVO();
            vo.setReservationId(r.getId());
            vo.setId(record == null ? null : record.getId());
            vo.setClassroomId(r.getClassroomId());
            Classroom room = classroomMap.get(r.getClassroomId());
            if (room != null) {
                vo.setClassroomName(room.getName());
                vo.setBuilding(room.getBuilding());
                vo.setRoomNo(room.getRoomNo());
            }
            vo.setUserId(r.getUserId());
            SysUser user = userMap.get(r.getUserId());
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setRealName(user.getName());
            }
            vo.setUseDate(record == null ? r.getReserveDate() : record.getUseDate());
            vo.setStartTime(r.getStartTime());
            vo.setEndTime(r.getEndTime());
            vo.setPeopleCount(r.getPeopleCount() == null ? 1 : r.getPeopleCount());
            vo.setPurpose(r.getPurpose());
            if (record != null) {
                vo.setActualPeople(record.getActualPeople());
                vo.setUseStatus(record.getUseStatus());
                vo.setUseStatusLabel(USE_STATUS_LABELS.getOrDefault(record.getUseStatus(), "-"));
                vo.setCheckRemark(record.getCheckRemark());
                vo.setCheckTime(record.getCheckTime());
                vo.setCreateTime(record.getCreateTime());
                SysUser registrar = userMap.get(record.getRegistrarId());
                vo.setRegistrarName(registrar == null ? null : registrar.getName());
            }
            result.add(vo);
        }
        result.sort(Comparator.comparing(UsageRecordVO::getUseDate, Comparator.reverseOrder())
                .thenComparing(UsageRecordVO::getStartTime, Comparator.reverseOrder()));
        return result;
    }

    /**
     * 日期区间解析：默认最近 30 天；跨度上限 366 天（与看板一致）
     */
    private LocalDate[] resolveRange(String startDate, String endDate) {
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(29));
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BusinessException("查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        return new LocalDate[]{start, end};
    }

    private LocalDate parseDate(String value, LocalDate defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("日期格式应为 yyyy-MM-dd");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 供统计模块复用的状态标签
     */
    static String labelOf(Integer useStatus) {
        return USE_STATUS_LABELS.getOrDefault(useStatus, "-");
    }

    /**
     * 供统计模块复用的空时间占位（避免 LocalTime null 比较异常）
     */
    static LocalTime safeTime(LocalTime time) {
        return time == null ? LocalTime.MIDNIGHT : time;
    }
}
