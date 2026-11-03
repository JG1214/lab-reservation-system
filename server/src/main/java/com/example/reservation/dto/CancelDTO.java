package com.example.reservation.dto;

import lombok.Data;

/**
 * 取消预约请求参数
 * <p>
 * 取消原因必填（≤255 字），落库到 reservation.cancel_reason / cancel_time，
 * 供「取消与失约」统计口径使用（需求设计文档 V4.0 规则 5）。
 *
 * @author reservation-team
 */
@Data
public class CancelDTO {

    /** 取消原因（必填） */
    private String reason;
}
