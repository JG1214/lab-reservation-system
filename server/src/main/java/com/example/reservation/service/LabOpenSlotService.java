package com.example.reservation.service;

import com.example.reservation.dto.LabOpenSlotDTO;
import com.example.reservation.entity.LabOpenSlot;
import com.example.reservation.vo.LabOpenSlotVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 实验室开放时段服务
 * <p>
 * 职责：维护实验室可预约日期、开放时间与预约人数上限，
 * 并为预约提交/审核提供时段合法性校验与人数上限查询。
 *
 * @author reservation-team
 */
public interface LabOpenSlotService {

    /**
     * 管理端：查询开放时段列表（可按实验室、状态筛选）
     *
     * @param classroomId 实验室 ID，可为空表示全部
     * @param status      状态，可为空表示全部
     * @return 时段视图列表
     */
    List<LabOpenSlotVO> listSlots(Long classroomId, Integer status);

    /**
     * 新增开放时段
     *
     * @param dto 时段参数
     * @return 新增时段 ID
     */
    Long createSlot(LabOpenSlotDTO dto);

    /**
     * 编辑开放时段
     *
     * @param dto 时段参数（必须携带 id）
     */
    void updateSlot(LabOpenSlotDTO dto);

    /**
     * 删除开放时段；存在未过期的有效预约（待审核/已通过）时禁止删除，只能停用
     *
     * @param id 时段 ID
     */
    void deleteSlot(Long id);

    /**
     * 批量生成开放时段：为指定实验室在一段日期内按星期批量创建
     *
     * @param classroomIds 实验室 ID 列表
     * @param dto          时段模板（忽略其中的 id 与 classroomId）
     * @return 实际新增的时段条数
     */
    int batchCreateSlots(List<Long> classroomIds, LabOpenSlotDTO dto);

    /**
     * 学生端：查询某实验室某日期生效的开放时段
     *
     * @param classroomId 实验室 ID
     * @param date        查询日期
     * @return 该日期生效的时段列表（仅启用状态）
     */
    List<LabOpenSlotVO> listEffectiveSlots(Long classroomId, LocalDate date);

    /**
     * 校验预约时间是否落在某个生效时段窗口内（规则 3）
     * <p>
     * 校验不通过时抛出 BusinessException，message 直接面向用户。
     *
     * @param classroomId 实验室 ID
     * @param date        预约日期
     * @param start       预约开始时间
     * @param end         预约结束时间
     * @return 命中的开放时段（调用方可从中读取 slotCapacity）
     */
    LabOpenSlot requireEffectiveSlot(Long classroomId, LocalDate date, LocalTime start, LocalTime end);

    /**
     * 查询某实验室某日某时段的预约人数上限（规则 2）
     * <p>
     * 优先级：命中时段的 slotCapacity &gt; classroom.capacity
     *
     * @param classroomId 实验室 ID
     * @param date        预约日期
     * @param start       预约开始时间
     * @param end         预约结束时间
     * @return 有效人数上限
     */
    int resolveCapacityLimit(Long classroomId, LocalDate date, LocalTime start, LocalTime end);

    /**
     * 查询某实验室某日期生效的开放时段实体列表
     * <p>
     * 供容量校验按「同一开放时段」累计已通过人数使用：时段窗口互不重叠，
     * 因此与任一窗口重叠的已通过预约即视为占用该窗口。
     *
     * @param classroomId 实验室 ID
     * @param date        查询日期
     * @return 生效时段实体列表，无生效时段时返回空列表
     */
    List<LabOpenSlot> listEffectiveSlotEntities(Long classroomId, LocalDate date);

    /**
     * 批量查询多个实验室某日期生效的开放时段实体
     * <p>
     * 供学生端教室列表一次查库计算「今日剩余可约人数」使用，避免逐教室 N+1 查询。
     *
     * @param classroomIds 实验室 ID 列表
     * @param date         查询日期
     * @return 实验室 ID → 生效时段实体列表（无生效时段的实验室不出现在 Map 中）
     */
    Map<Long, List<LabOpenSlot>> listEffectiveSlotEntities(List<Long> classroomIds, LocalDate date);
}
