package com.example.reservation.controller;

import com.alibaba.excel.EasyExcel;
import com.example.reservation.common.Result;
import com.example.reservation.dto.UsageRecordDTO;
import com.example.reservation.service.UsageRecordService;
import com.example.reservation.vo.UsageExportVO;
import com.example.reservation.vo.UsageRecordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 使用登记接口（仅实验室管理员，鉴权由 AuthInterceptor 的 /api/usage 前缀统一处理）
 *
 * @author reservation-team
 */
@Tag(name = "使用登记", description = "待登记列表、实际使用登记、批量失约、使用记录查询与导出")
@RestController
@RequestMapping("/api/usage")
public class UsageRecordController {

    @Resource
    private UsageRecordService usageRecordService;

    @Operation(summary = "使用登记-待登记列表", description = "已通过且使用日期已过、尚未登记的预约")
    @GetMapping("/pending")
    public Result<List<UsageRecordVO>> listPending(@RequestParam(required = false) String date) {
        return Result.success(usageRecordService.listPending(date));
    }

    @Operation(summary = "使用登记-登记", description = "登记实际使用人数与使用状态；同一预约仅可登记一次")
    @PostMapping
    public Result<Void> register(@RequestBody UsageRecordDTO dto) {
        usageRecordService.register(dto);
        return Result.<Void>success("登记成功", null);
    }

    @Operation(summary = "使用登记-批量失约", description = "将多条已通过预约一次性登记为失约")
    @PostMapping("/batch-noshow")
    public Result<Integer> batchNoShow(@RequestBody NoShowBatchRequest request) {
        int updated = usageRecordService.batchNoShow(request == null ? null : request.getReservationIds(),
                request == null ? null : request.getRemark());
        return Result.success("已标记失约", updated);
    }

    @Operation(summary = "使用登记-记录查询", description = "按日期区间/使用状态/实验室筛选使用记录")
    @GetMapping("/manage")
    public Result<List<UsageRecordVO>> listRecords(@RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate,
                                                   @RequestParam(required = false) Integer useStatus,
                                                   @RequestParam(required = false) Long classroomId) {
        return Result.success(usageRecordService.listRecords(startDate, endDate, useStatus, classroomId));
    }

    @Operation(summary = "使用登记-记录导出", description = "按筛选条件导出使用记录为 Excel（xlsx），返回文件流")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       @RequestParam(required = false) Integer useStatus,
                       @RequestParam(required = false) Long classroomId,
                       HttpServletResponse response) throws IOException {
        List<UsageRecordVO> list = usageRecordService.listExport(startDate, endDate, useStatus, classroomId);
        // 文件名带时间戳避免覆盖；中文按 RFC 5987 编码（与预约导出口径一致）
        String fileName = "使用记录_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"usage.xlsx\"; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        EasyExcel.write(response.getOutputStream(), UsageExportVO.class)
                .sheet("使用记录")
                .doWrite(list.stream().map(UsageExportVO::of).toList());
    }

    /**
     * 批量失约入参
     */
    public static class NoShowBatchRequest {
        private List<Long> reservationIds;
        private String remark;

        public List<Long> getReservationIds() {
            return reservationIds;
        }

        public void setReservationIds(List<Long> reservationIds) {
            this.reservationIds = reservationIds;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }
}
