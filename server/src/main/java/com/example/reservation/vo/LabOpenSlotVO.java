package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 开放时段视图对象（管理端列表 / 学生端教室详情共用）
 *
 * @author reservation-team
 */
@Data
public class LabOpenSlotVO {

    /** 主键 ID */
    private Long id;

    /** 实验室 ID */
    private Long classroomId;

    /** 实验室名称（列表展示用，冗余字段） */
    private String classroomName;

    /** 实验室编号（列表展示用，冗余字段） */
    private String roomNo;

    /** 开放起始日期 */
    private LocalDate startDate;

    /** 开放结束日期 */
    private LocalDate endDate;

    /** 开放星期：1-7 逗号分隔 */
    private String weekDays;

    /** 开放星期的中文可读形式，如「周一、周二、周三」 */
    private String weekDaysLabel;

    /** 开放开始时间 */
    private LocalTime openTime;

    /** 开放结束时间 */
    private LocalTime closeTime;

    /** 该时段预约人数上限 */
    private Integer slotCapacity;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
