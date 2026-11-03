package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.Constants;
import com.example.reservation.dto.LabOpenSlotDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.LabOpenSlot;
import com.example.reservation.entity.Reservation;
import com.example.reservation.common.BusinessException;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.LabOpenSlotMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.service.LabOpenSlotService;
import com.example.reservation.vo.LabOpenSlotVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实验室开放时段服务实现
 * <p>
 * 核心规则（需求设计文档 V4.0 规则 3）：
 * 预约日期须落在 [startDate, endDate] 且星期命中 weekDays；
 * 预约起止时间须完整落在某个 (openTime, closeTime) 窗口内，且不得跨窗口；
 * 无生效时段的实验室不可预约。
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class LabOpenSlotServiceImpl implements LabOpenSlotService {

    /** 星期中文标签，下标 1-7 对应周一至周日 */
    private static final String[] WEEK_DAY_LABELS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Resource
    private LabOpenSlotMapper labOpenSlotMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Override
    public List<LabOpenSlotVO> listSlots(Long classroomId, Integer status) {
        LambdaQueryWrapper<LabOpenSlot> wrapper = new LambdaQueryWrapper<LabOpenSlot>()
                .eq(classroomId != null, LabOpenSlot::getClassroomId, classroomId)
                .eq(status != null, LabOpenSlot::getStatus, status)
                .orderByAsc(LabOpenSlot::getClassroomId)
                .orderByAsc(LabOpenSlot::getOpenTime);
        return toVOList(labOpenSlotMapper.selectList(wrapper));
    }

    @Override
    public Long createSlot(LabOpenSlotDTO dto) {
        if (dto == null) {
            throw new BusinessException("时段配置不能为空");
        }
        validateSlotDTO(dto, null);
        LabOpenSlot slot = new LabOpenSlot();
        slot.setClassroomId(dto.getClassroomId());
        slot.setStartDate(dto.getStartDate());
        slot.setEndDate(dto.getEndDate());
        slot.setWeekDays(normalizeWeekDays(dto.getWeekDays()));
        slot.setOpenTime(dto.getOpenTime());
        slot.setCloseTime(dto.getCloseTime());
        slot.setSlotCapacity(dto.getSlotCapacity());
        slot.setStatus(dto.getStatus() == null ? Constants.SLOT_STATUS_ENABLED : dto.getStatus());
        slot.setRemark(dto.getRemark());
        labOpenSlotMapper.insert(slot);
        log.info("新增开放时段：slotId={}, classroomId={}, {}~{}", slot.getId(), slot.getClassroomId(),
                slot.getOpenTime(), slot.getCloseTime());
        return slot.getId();
    }

    @Override
    public void updateSlot(LabOpenSlotDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("时段 ID 不能为空");
        }
        LabOpenSlot exists = labOpenSlotMapper.selectById(dto.getId());
        if (exists == null) {
            throw new BusinessException("开放时段不存在");
        }
        validateSlotDTO(dto, dto.getId());
        LabOpenSlot slot = new LabOpenSlot();
        slot.setId(dto.getId());
        slot.setClassroomId(dto.getClassroomId());
        slot.setStartDate(dto.getStartDate());
        slot.setEndDate(dto.getEndDate());
        slot.setWeekDays(normalizeWeekDays(dto.getWeekDays()));
        slot.setOpenTime(dto.getOpenTime());
        slot.setCloseTime(dto.getCloseTime());
        slot.setSlotCapacity(dto.getSlotCapacity());
        slot.setStatus(dto.getStatus() == null ? exists.getStatus() : dto.getStatus());
        slot.setRemark(dto.getRemark());
        labOpenSlotMapper.updateById(slot);
        log.info("编辑开放时段：slotId={}, classroomId={}", slot.getId(), slot.getClassroomId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSlot(Long id) {
        if (id == null) {
            throw new BusinessException("时段 ID 不能为空");
        }
        LabOpenSlot slot = labOpenSlotMapper.selectById(id);
        if (slot == null) {
            throw new BusinessException("开放时段不存在");
        }
        // 存在未过期的有效预约（待审核/已通过）时禁止物理删除，否则历史预约将失去时段依据
        Long activeCount = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, slot.getClassroomId())
                .in(Reservation::getStatus, Constants.RES_STATUS_PENDING, Constants.RES_STATUS_APPROVED)
                .ge(Reservation::getReserveDate, LocalDate.now()));
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException("该实验室存在 " + activeCount + " 条未过期的有效预约，无法删除时段，请改为停用");
        }
        labOpenSlotMapper.deleteById(id);
        log.info("删除开放时段：slotId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchCreateSlots(List<Long> classroomIds, LabOpenSlotDTO dto) {
        if (dto == null) {
            throw new BusinessException("时段配置不能为空");
        }
        if (classroomIds == null || classroomIds.isEmpty()) {
            throw new BusinessException("请至少选择一个实验室");
        }
        int created = 0;
        for (Long classroomId : new LinkedHashSet<>(classroomIds)) {
            LabOpenSlotDTO single = new LabOpenSlotDTO();
            single.setClassroomId(classroomId);
            single.setStartDate(dto.getStartDate());
            single.setEndDate(dto.getEndDate());
            single.setWeekDays(dto.getWeekDays());
            single.setOpenTime(dto.getOpenTime());
            single.setCloseTime(dto.getCloseTime());
            single.setSlotCapacity(dto.getSlotCapacity());
            single.setStatus(dto.getStatus());
            single.setRemark(dto.getRemark());
            try {
                createSlot(single);
                created++;
            } catch (BusinessException e) {
                // 单个实验室校验失败不影响其余实验室，跳过并记录
                log.warn("批量生成时段跳过 classroomId={}：{}", classroomId, e.getMessage());
            }
        }
        if (created == 0) {
            throw new BusinessException("所选实验室均未能生成时段，请检查日期区间或时段是否与已有配置重叠");
        }
        return created;
    }

    @Override
    public List<LabOpenSlotVO> listEffectiveSlots(Long classroomId, LocalDate date) {
        if (classroomId == null || date == null) {
            throw new BusinessException("实验室与日期不能为空");
        }
        // 生效判定：状态启用 + 日期落在区间内 + 星期命中，统一走 listEffectiveSlotEntities
        List<LabOpenSlotVO> result = new ArrayList<>();
        for (LabOpenSlot slot : listEffectiveSlotEntities(classroomId, date)) {
            result.addAll(toVOList(List.of(slot)));
        }
        return result;
    }

    @Override
    public LabOpenSlot requireEffectiveSlot(Long classroomId, LocalDate date, LocalTime start, LocalTime end) {
        List<LabOpenSlot> candidates = listEffectiveSlotEntities(classroomId, date);
        if (candidates.isEmpty()) {
            throw new BusinessException("该实验室在所选日期未开放，请选择其他日期");
        }
        // 规则 3：预约必须完整落在某一个窗口内，不得跨窗口
        for (LabOpenSlot slot : candidates) {
            if (!start.isBefore(slot.getOpenTime()) && !end.isAfter(slot.getCloseTime())) {
                return slot;
            }
        }
        String windows = candidates.stream()
                .map(s -> s.getOpenTime() + "-" + s.getCloseTime())
                .collect(Collectors.joining("、"));
        throw new BusinessException("所选时间须完整落在开放时段内（当日开放：" + windows + "），不可跨时段预约");
    }

    @Override
    public int resolveCapacityLimit(Long classroomId, LocalDate date, LocalTime start, LocalTime end) {
        // 规则 2：优先取命中时段的人数上限，未配置时段时回落到实验室容量
        List<LabOpenSlot> candidates = listEffectiveSlotEntities(classroomId, date);
        for (LabOpenSlot slot : candidates) {
            if (!start.isBefore(slot.getOpenTime()) && !end.isAfter(slot.getCloseTime())
                    && slot.getSlotCapacity() != null) {
                return slot.getSlotCapacity();
            }
        }
        Classroom classroom = classroomMapper.selectById(classroomId);
        if (classroom == null || classroom.getCapacity() == null) {
            throw new BusinessException("实验室不存在或未配置容量");
        }
        return classroom.getCapacity();
    }

    /**
     * 查询某实验室某日期生效的时段实体（时段校验、容量校验共用，避免多次查库）
     */
    @Override
    public List<LabOpenSlot> listEffectiveSlotEntities(Long classroomId, LocalDate date) {
        int dayOfWeek = toWeekDayValue(date.getDayOfWeek());
        List<LabOpenSlot> slots = labOpenSlotMapper.selectList(new LambdaQueryWrapper<LabOpenSlot>()
                .eq(LabOpenSlot::getClassroomId, classroomId)
                .eq(LabOpenSlot::getStatus, Constants.SLOT_STATUS_ENABLED)
                .le(LabOpenSlot::getStartDate, date)
                .ge(LabOpenSlot::getEndDate, date)
                .orderByAsc(LabOpenSlot::getOpenTime));
        return slots.stream().filter(s -> parseWeekDays(s.getWeekDays()).contains(dayOfWeek)).toList();
    }

    /**
     * 批量查询多个实验室某日期生效的时段实体（单次查库，生效判定与单实验室版本同源）
     */
    @Override
    public Map<Long, List<LabOpenSlot>> listEffectiveSlotEntities(List<Long> classroomIds, LocalDate date) {
        if (classroomIds == null || classroomIds.isEmpty() || date == null) {
            return Map.of();
        }
        int dayOfWeek = toWeekDayValue(date.getDayOfWeek());
        List<LabOpenSlot> slots = labOpenSlotMapper.selectList(new LambdaQueryWrapper<LabOpenSlot>()
                .in(LabOpenSlot::getClassroomId, classroomIds)
                .eq(LabOpenSlot::getStatus, Constants.SLOT_STATUS_ENABLED)
                .le(LabOpenSlot::getStartDate, date)
                .ge(LabOpenSlot::getEndDate, date)
                .orderByAsc(LabOpenSlot::getOpenTime));
        return slots.stream()
                .filter(s -> parseWeekDays(s.getWeekDays()).contains(dayOfWeek))
                .collect(Collectors.groupingBy(LabOpenSlot::getClassroomId));
    }

    /**
     * 时段表单校验（新增/编辑共用）
     *
     * @param dto     时段参数
     * @param excludeId 编辑时排除自身 ID，新增传 null
     */
    private void validateSlotDTO(LabOpenSlotDTO dto, Long excludeId) {
        if (dto.getClassroomId() == null) {
            throw new BusinessException("请选择实验室");
        }
        Classroom classroom = classroomMapper.selectById(dto.getClassroomId());
        if (classroom == null) {
            throw new BusinessException("实验室不存在");
        }
        if (classroom.getStatus() == null || classroom.getStatus() != Constants.CLASSROOM_STATUS_ENABLED) {
            throw new BusinessException("该实验室已停用，无法配置开放时段");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null) {
            throw new BusinessException("开放起止日期不能为空");
        }
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException("开放起始日期不能晚于结束日期");
        }
        if (dto.getOpenTime() == null || dto.getCloseTime() == null) {
            throw new BusinessException("开放起止时间不能为空");
        }
        if (!dto.getOpenTime().isBefore(dto.getCloseTime())) {
            throw new BusinessException("开放开始时间必须早于结束时间");
        }
        if (dto.getSlotCapacity() == null) {
            throw new BusinessException("预约人数上限不能为空");
        }
        if (dto.getSlotCapacity() <= 0) {
            throw new BusinessException("预约人数上限必须大于 0");
        }
        if (classroom.getCapacity() != null && dto.getSlotCapacity() > classroom.getCapacity()) {
            throw new BusinessException("预约人数上限不能超过实验室容量（" + classroom.getCapacity() + " 人）");
        }
        Set<Integer> days = parseWeekDays(dto.getWeekDays());
        if (days.isEmpty()) {
            throw new BusinessException("请至少选择一个开放星期");
        }

        // 同实验室时段不得重叠：日期区间有交集 且 时间窗口有交集 且 星期有交集
        List<LabOpenSlot> others = labOpenSlotMapper.selectList(new LambdaQueryWrapper<LabOpenSlot>()
                .eq(LabOpenSlot::getClassroomId, dto.getClassroomId())
                .ne(excludeId != null, LabOpenSlot::getId, excludeId));
        for (LabOpenSlot other : others) {
            boolean dateOverlap = !dto.getStartDate().isAfter(other.getEndDate())
                    && !dto.getEndDate().isBefore(other.getStartDate());
            if (!dateOverlap) {
                continue;
            }
            // 时间窗口重叠判定沿用冲突公式：新开始 < 旧结束 AND 新结束 > 旧开始
            boolean timeOverlap = dto.getOpenTime().isBefore(other.getCloseTime())
                    && dto.getCloseTime().isAfter(other.getOpenTime());
            if (!timeOverlap) {
                continue;
            }
            Set<Integer> otherDays = parseWeekDays(other.getWeekDays());
            boolean dayOverlap = days.stream().anyMatch(otherDays::contains);
            if (dayOverlap) {
                throw new BusinessException("该实验室在相同日期与星期下已存在重叠时段（时段 ID：" + other.getId() + "）");
            }
        }

        // 编辑时若下调人数上限，不得低于该时段已有通过预约的峰值占用
        if (excludeId != null) {
            int peak = peakApprovedPeople(dto.getClassroomId(), dto.getStartDate(), dto.getEndDate(),
                    dto.getOpenTime(), dto.getCloseTime());
            if (peak > dto.getSlotCapacity()) {
                throw new BusinessException("该时段已有预约的峰值人数为 " + peak + " 人，人数上限不能低于该值");
            }
        }
    }

    /**
     * 统计某实验室在给定日期区间与时间窗口内、已通过预约的人数峰值（按日期+开始时间聚合近似）
     */
    private int peakApprovedPeople(Long classroomId, LocalDate startDate, LocalDate endDate,
                                   LocalTime openTime, LocalTime closeTime) {
        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, classroomId)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                .between(Reservation::getReserveDate, startDate, endDate));
        Map<String, Integer> perSlot = new LinkedHashSet<>(approved).stream()
                .filter(r -> !r.getStartTime().isBefore(openTime) && !r.getEndTime().isAfter(closeTime))
                .collect(Collectors.groupingBy(
                        r -> r.getReserveDate() + "|" + r.getStartTime(),
                        Collectors.summingInt(r -> r.getPeopleCount() == null ? 1 : r.getPeopleCount())));
        return perSlot.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * 解析星期字符串为去重后的整数集合；非法内容被忽略，交由调用方判断是否为空
     */
    private Set<Integer> parseWeekDays(String weekDays) {
        Set<Integer> result = new LinkedHashSet<>();
        if (weekDays == null || weekDays.isBlank()) {
            return result;
        }
        for (String part : weekDays.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value >= 1 && value <= 7) {
                    result.add(value);
                }
            } catch (NumberFormatException ignored) {
                // 非法片段忽略
            }
        }
        return result;
    }

    /**
     * 规范化星期字符串：去重、升序、逗号拼接
     */
    private String normalizeWeekDays(String weekDays) {
        Set<Integer> days = parseWeekDays(weekDays);
        if (days.isEmpty()) {
            throw new BusinessException("开放星期格式不合法，应为 1-7 的逗号分隔值");
        }
        return days.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * java.time.DayOfWeek 转 1-7（1=周一，7=周日），与 week_days 字段口径一致
     */
    private int toWeekDayValue(DayOfWeek dayOfWeek) {
        return dayOfWeek.getValue();
    }

    /**
     * 实体列表转视图列表，并补齐实验室名称与星期中文标签
     */
    private List<LabOpenSlotVO> toVOList(List<LabOpenSlot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        List<Long> classroomIds = slots.stream().map(LabOpenSlot::getClassroomId).distinct().toList();
        Map<Long, Classroom> classroomMap = classroomMapper.selectBatchIds(classroomIds).stream()
                .collect(Collectors.toMap(Classroom::getId, Function.identity()));
        List<LabOpenSlotVO> result = new ArrayList<>(slots.size());
        for (LabOpenSlot slot : slots) {
            LabOpenSlotVO vo = new LabOpenSlotVO();
            vo.setId(slot.getId());
            vo.setClassroomId(slot.getClassroomId());
            Classroom classroom = classroomMap.get(slot.getClassroomId());
            if (classroom != null) {
                vo.setClassroomName(classroom.getName());
                vo.setRoomNo(classroom.getRoomNo());
            }
            vo.setStartDate(slot.getStartDate());
            vo.setEndDate(slot.getEndDate());
            vo.setWeekDays(slot.getWeekDays());
            vo.setWeekDaysLabel(buildWeekDaysLabel(slot.getWeekDays()));
            vo.setOpenTime(slot.getOpenTime());
            vo.setCloseTime(slot.getCloseTime());
            vo.setSlotCapacity(slot.getSlotCapacity());
            vo.setStatus(slot.getStatus());
            vo.setRemark(slot.getRemark());
            vo.setCreateTime(slot.getCreateTime());
            vo.setUpdateTime(slot.getUpdateTime());
            result.add(vo);
        }
        return result;
    }

    /**
     * 星期中文标签，如 "1,2,3" → "周一、周二、周三"
     */
    private String buildWeekDaysLabel(String weekDays) {
        Set<Integer> days = parseWeekDays(weekDays);
        if (days.isEmpty()) {
            return "";
        }
        return Arrays.stream(days.stream().sorted().mapToInt(Integer::intValue).toArray())
                .mapToObj(d -> WEEK_DAY_LABELS[d])
                .collect(Collectors.joining("、"));
    }
}
