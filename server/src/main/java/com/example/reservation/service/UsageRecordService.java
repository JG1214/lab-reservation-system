package com.example.reservation.service;

import com.example.reservation.dto.UsageRecordDTO;
import com.example.reservation.vo.UsageRecordVO;

import java.util.List;

/**
 * 使用登记服务（需求设计文档 V4.0 1.3 管理端「使用登记」页）
 *
 * @author reservation-team
 */
public interface UsageRecordService {

    /**
     * 待登记列表：已通过(1) 且使用日期已过（含今日已结束）且尚未登记的预约
     *
     * @param date 可选，按使用日期精确过滤
     */
    List<UsageRecordVO> listPending(String date);

    /**
     * 使用登记（单条）
     */
    void register(UsageRecordDTO dto);

    /**
     * 批量标记失约
     */
    int batchNoShow(List<Long> reservationIds, String remark);

    /**
     * 使用记录查询（分页由前端切片，数据量受日期区间约束）
     */
    List<UsageRecordVO> listRecords(String startDate, String endDate, Integer useStatus, Long classroomId);

    /**
     * 使用记录导出数据（EasyExcel 用）
     */
    List<UsageRecordVO> listExport(String startDate, String endDate, Integer useStatus, Long classroomId);
}
