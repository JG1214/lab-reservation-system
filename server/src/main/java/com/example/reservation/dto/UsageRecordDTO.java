package com.example.reservation.dto;

import lombok.Data;

/**
 * 使用登记入参（需求设计文档 V4.0 1.3 管理端「使用登记」页）
 *
 * <p>校验规则见《接口定义清单-V4.0》5.1：预约须为已通过(1)；同一预约不可重复登记；
 * useStatus=3 时 actualPeople 必须为 0；useStatus≠3 时 1≤actualPeople≤peopleCount；
 * useStatus=2 时 actualPeople 必须小于 peopleCount。
 *
 * @author reservation-team
 */
@Data
public class UsageRecordDTO {

    /** 预约 ID */
    private Long reservationId;

    /** 实际使用人数 */
    private Integer actualPeople;

    /** 使用状态：1-正常使用，2-部分使用，3-失约 */
    private Integer useStatus;

    /** 登记备注（失约时建议填写原因） */
    private String checkRemark;
}
