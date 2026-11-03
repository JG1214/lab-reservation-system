package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 使用记录实体（需求设计文档 V4.0 2.3.4 表 7 usage_record）
 *
 * <p>一条「已通过(1)」的预约最多对应一条使用记录（uk_reservation 唯一约束兜底）。
 * 失约不新增预约状态，而是以 use_status=3 记录在本表，保证预约状态机仍为 4 态。
 *
 * @author reservation-team
 */
@Data
@TableName("usage_record")
public class UsageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联预约 ID（唯一） */
    private Long reservationId;

    /** 实验室 ID */
    private Long classroomId;

    /** 预约使用人 ID */
    private Long userId;

    /** 预约使用日期 */
    private LocalDate useDate;

    /** 实际使用人数（失约时为 0） */
    private Integer actualPeople;

    /** 使用状态：1-正常使用，2-部分使用，3-失约 */
    private Integer useStatus;

    /** 登记备注 */
    private String checkRemark;

    /** 登记人（实验室管理员）ID */
    private Long registrarId;

    /** 登记时间 */
    private LocalDateTime checkTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
