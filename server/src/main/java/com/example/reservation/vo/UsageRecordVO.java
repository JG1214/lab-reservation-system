package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 使用记录视图对象（管理端「使用登记」列表）
 *
 * <p>列表需要同时呈现「预约原文」与「登记结果」，供管理员核对人数差，
 * 因此预约侧字段（教室、使用人、日期、时段、预约人数、用途）以扁平字段冗余返回，
 * 避免前端二次请求拼装。
 *
 * @author reservation-team
 */
@Data
public class UsageRecordVO {

    private Long id;

    private Long reservationId;

    private Long classroomId;

    /** 实验室名称 */
    private String classroomName;

    /** 楼栋 */
    private String building;

    /** 房间号 */
    private String roomNo;

    private Long userId;

    /** 使用人账号 */
    private String username;

    /** 使用人姓名（字段名不用 userName：Lombok 会与 username 生成同名 getter） */
    private String realName;

    /** 使用日期 */
    private LocalDate useDate;

    /** 预约开始时间 */
    private LocalTime startTime;

    /** 预约结束时间 */
    private LocalTime endTime;

    /** 预约人数 */
    private Integer peopleCount;

    /** 实际使用人数 */
    private Integer actualPeople;

    /** 使用状态：1-正常使用，2-部分使用，3-失约 */
    private Integer useStatus;

    /** 使用状态中文（正常使用/部分使用/失约） */
    private String useStatusLabel;

    /** 登记备注 */
    private String checkRemark;

    /** 登记人姓名 */
    private String registrarName;

    /** 登记时间 */
    private LocalDateTime checkTime;

    /** 预约用途 */
    private String purpose;

    private LocalDateTime createTime;
}
