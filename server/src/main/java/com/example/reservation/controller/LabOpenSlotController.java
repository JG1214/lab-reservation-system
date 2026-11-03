package com.example.reservation.controller;

import com.example.reservation.common.Result;
import com.example.reservation.dto.LabOpenSlotDTO;
import com.example.reservation.service.LabOpenSlotService;
import com.example.reservation.vo.LabOpenSlotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验室开放时段控制器
 * <p>
 * 管理端接口位于 /api/slot/manage 与 /api/slot（均已加入 ADMIN_API_PREFIXES，仅管理员可访问）；
 * 学生端查询接口为 /api/slot/classroom/{classroomId}，登录即可访问。
 *
 * @author reservation-team
 */
@Tag(name = "开放时段管理")
@RestController
@RequestMapping("/api/slot")
public class LabOpenSlotController {

    @Resource
    private LabOpenSlotService labOpenSlotService;

    @Operation(summary = "管理端：开放时段列表")
    @GetMapping("/manage")
    public Result<List<LabOpenSlotVO>> list(@RequestParam(required = false) Long classroomId,
                                            @RequestParam(required = false) Integer status) {
        return Result.success(labOpenSlotService.listSlots(classroomId, status));
    }

    @Operation(summary = "新增开放时段")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody LabOpenSlotDTO dto) {
        return Result.success(labOpenSlotService.createSlot(dto));
    }

    @Operation(summary = "编辑开放时段")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody LabOpenSlotDTO dto) {
        dto.setId(id);
        labOpenSlotService.updateSlot(dto);
        return Result.success(null);
    }

    @Operation(summary = "删除开放时段")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        labOpenSlotService.deleteSlot(id);
        return Result.success(null);
    }

    @Operation(summary = "批量生成开放时段")
    @PostMapping("/batch")
    public Result<Integer> batchCreate(@Valid @RequestBody SlotBatchRequest request) {
        int created = labOpenSlotService.batchCreateSlots(request.getClassroomIds(), request.toDTO());
        return Result.success(created);
    }

    @Operation(summary = "学生端：查询某实验室某日期的开放时段")
    @GetMapping("/classroom/{classroomId}")
    public Result<List<LabOpenSlotVO>> listByClassroom(@PathVariable Long classroomId,
                                                       @RequestParam String date) {
        return Result.success(labOpenSlotService.listEffectiveSlots(classroomId, LocalDate.parse(date)));
    }

    /**
     * 批量生成时段请求体：时段模板 + 实验室 ID 列表
     */
    @Data
    public static class SlotBatchRequest {

        /** 目标实验室 ID 列表 */
        private List<Long> classroomIds = new ArrayList<>();

        /** 开放起始日期 */
        private LocalDate startDate;

        /** 开放结束日期 */
        private LocalDate endDate;

        /** 开放星期：1-7 逗号分隔 */
        private String weekDays;

        /** 开放开始时间 */
        private java.time.LocalTime openTime;

        /** 开放结束时间 */
        private java.time.LocalTime closeTime;

        /** 该时段预约人数上限 */
        private Integer slotCapacity;

        /** 状态：0-停用，1-启用 */
        private Integer status;

        /** 备注 */
        private String remark;

        /**
         * 转换为时段 DTO（忽略 id 与 classroomId，由服务逐个填充）
         */
        public LabOpenSlotDTO toDTO() {
            LabOpenSlotDTO dto = new LabOpenSlotDTO();
            dto.setStartDate(startDate);
            dto.setEndDate(endDate);
            dto.setWeekDays(weekDays);
            dto.setOpenTime(openTime);
            dto.setCloseTime(closeTime);
            dto.setSlotCapacity(slotCapacity);
            dto.setStatus(status);
            dto.setRemark(remark);
            return dto;
        }
    }
}
