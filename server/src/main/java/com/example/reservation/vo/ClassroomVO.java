package com.example.reservation.vo;

import lombok.Data;

import java.util.List;

/**
 * 学生端教室视图对象（R3 教室浏览）
 * 含实时状态标签与指定日期已通过预约占用时段
 *
 * @author reservation-team
 */
@Data
public class ClassroomVO {

    /** 主键 ID */
    private Long id;

    /** 教室名称 */
    private String name;

    /** 所属楼栋 */
    private String building;

    /** 教室编号 */
    private String roomNo;

    /** 类型：1-普通教室，2-实验室，3-机房 */
    private Integer type;

    /** 容纳人数 */
    private Integer capacity;

    /** 设备说明 */
    private String equipment;

    /** 备注描述 */
    private String description;

    /** 开放要求（准入门槛，学生端教室详情页展示） */
    private String openRequirement;

    /** 状态：0-停用，1-可用（学生端列表仅返回可用教室） */
    private Integer status;

    /** 实时状态标签：当前空闲 / 使用中（口径见 ClassroomService 实现注释） */
    private String statusLabel;

    /** 今日剩余可约人数（需求文档 V4.1 2.5 第 6 条；容量口径与教室详情页开放时段 slotCapacity 同源）：
     *  Σ(今日生效且未结束时段的 slotCapacity − 该时段已通过预约人数)，已过时段不计；无生效时段时为 0 */
    private Integer todayRemainingPeople;

    /** 指定日期（默认当天）该教室已通过预约占用时段 */
    private List<OccupiedSlotVO> occupiedSlots;
}
