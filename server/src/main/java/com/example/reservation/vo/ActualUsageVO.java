package com.example.reservation.vo;

import lombok.Data;

/**
 * 数据看板-实际使用统计视图对象（需求设计文档 V4.0 1.3 管理端「数据统计」）
 *
 * <p>口径（与 UsageRateVO 并列展示，互不覆盖）：
 * 预约使用率 = 已通过预约占用小时 ÷（天数 × 14h），反映「排期被占用的程度」；
 * 实际使用率 = 实际使用小时（按实际人数/预约人数折算）÷（天数 × 14h），反映「真实被使用的程度」；
 * 失约率 = 失约条数 ÷ 应到条数（已登记的正常+部分使用+失约）。
 *
 * @author reservation-team
 */
@Data
public class ActualUsageVO {

    /** 实验室 ID */
    private Long classroomId;

    private String name;

    private String building;

    private String roomNo;

    /** 类型：1-普通教室，2-实验室，3-机房 */
    private Integer type;

    /** 容纳人数 */
    private Integer capacity;

    /** 区间内已登记的预约条数（应到条数） */
    private Long recordedCount;

    /** 正常使用条数 */
    private Long normalCount;

    /** 部分使用条数 */
    private Long partialCount;

    /** 失约条数 */
    private Long noShowCount;

    /** 预约人数合计 */
    private Long plannedPeople;

    /** 实际使用人数合计 */
    private Long actualPeople;

    /** 实际使用小时数（按人数折算，保留 1 位小数） */
    private Double actualHours;

    /** 实际使用率（%，保留 1 位小数） */
    private Double actualUsageRate;

    /** 失约率（%，保留 1 位小数） */
    private Double noShowRate;

    /** 区间内已取消的预约条数（需求 V4.1 2.5 第 6 条「取消率」分子） */
    private Long cancelledCount;

    /** 取消率（%）= 已取消条数 ÷ 区间内非驳回条数（待审核 + 已通过 + 已取消），保留 1 位小数 */
    private Double cancelRate;
}
