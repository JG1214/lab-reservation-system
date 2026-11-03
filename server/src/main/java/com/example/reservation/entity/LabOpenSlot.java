package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 实验室开放时段实体（lab_open_slot）
 * <p>
 * 语义：某实验室在 [startDate, endDate] 日期区间内、按 weekDays 指定的星期几开放，
 * 每天在 (openTime, closeTime) 窗口内可被预约，该窗口的预约人数上限为 slotCapacity。
 * <p>
 * 业务规则（需求设计文档 V4.0 规则 3）：预约必须完整落在某个时段窗口内且不跨窗口；
 * 无时段配置的实验室不可预约。
 *
 * @author reservation-team
 */
@Data
@TableName("lab_open_slot")
public class LabOpenSlot {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实验室 ID（classroom.id） */
    private Long classroomId;

    /** 开放起始日期 */
    private LocalDate startDate;

    /** 开放结束日期 */
    private LocalDate endDate;

    /** 开放星期：1-7 逗号分隔（1=周一，7=周日） */
    private String weekDays;

    /** 开放开始时间 */
    private LocalTime openTime;

    /** 开放结束时间 */
    private LocalTime closeTime;

    /** 该时段预约人数上限（不得大于 classroom.capacity） */
    private Integer slotCapacity;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
