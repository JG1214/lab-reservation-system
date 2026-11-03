package com.example.reservation.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 冲突检测结果（R3 预约核心）
 *
 * @author reservation-team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictVO {

    /** 是否冲突 */
    private Boolean conflict;

    /** 冲突原因（不冲突时为可预约提示） */
    private String reason;

    /** 该时段人数上限（来自开放时段 slotCapacity，缺省回落到实验室容量） */
    private Integer capacityLimit;

    /** 该时段已被【已通过】预约占用的人数 */
    private Integer approvedPeople;

    /** 精简构造：仅冲突标记与原因 */
    public ConflictVO(Boolean conflict, String reason) {
        this.conflict = conflict;
        this.reason = reason;
    }
}
