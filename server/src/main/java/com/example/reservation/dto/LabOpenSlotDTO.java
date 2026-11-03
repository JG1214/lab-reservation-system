package com.example.reservation.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 开放时段新增/编辑请求参数（编辑时携带 id）
 * <p>
 * 校验规则见《接口定义清单 V4.0》第 3 节：
 * 实验室须存在且可用；startDate ≤ endDate；weekDays 为 1-7 逗号分隔；
 * openTime &lt; closeTime；1 ≤ slotCapacity ≤ classroom.capacity；
 * 同一实验室同时段区间不得重叠且 weekDays 不得有交集。
 *
 * @author reservation-team
 */
@Data
public class LabOpenSlotDTO {

    /** 时段 ID（编辑时必填） */
    private Long id;

    /** 实验室 ID（必填） */
    private Long classroomId;

    /** 开放起始日期（必填） */
    private LocalDate startDate;

    /** 开放结束日期（必填） */
    private LocalDate endDate;

    /** 开放星期（必填）：1-7 逗号分隔，如 "1,2,3,4,5" */
    private String weekDays;

    /** 开放开始时间（必填） */
    private LocalTime openTime;

    /** 开放结束时间（必填） */
    private LocalTime closeTime;

    /** 该时段预约人数上限（必填，≤ 实验室容量） */
    private Integer slotCapacity;

    /** 状态：0-停用，1-启用（选填，默认启用） */
    private Integer status;

    /** 备注（选填） */
    private String remark;
}
