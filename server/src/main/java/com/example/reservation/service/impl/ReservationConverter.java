package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UsageRecord;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UsageRecordMapper;
import com.example.reservation.vo.ReservationExportVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约实体 → VO 转换器
 * 由 ReservationServiceImpl 的私有转换方法外移而来（代码质量重构轮），行为不变：
 * 批量预查教室/用户信息，避免逐行 selectById 造成 N+1 查询
 *
 * @author reservation-team
 */
@Component
public class ReservationConverter {

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private UsageRecordMapper usageRecordMapper;

    /** 批量预查教室信息 → id→Classroom Map（空集合安全，避免逐行 selectById 造成 N+1） */
    public Map<Long, Classroom> batchClassroomMap(List<Reservation> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = list.stream().map(Reservation::getClassroomId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return classroomMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Classroom::getId, Function.identity(), (a, b) -> a));
    }

    /** 批量预查用户信息 → id→SysUser Map（空集合安全） */
    public Map<Long, SysUser> batchUserMap(List<Reservation> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = list.stream().map(Reservation::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    /** 批量预查使用记录 → reservationId→UsageRecord Map（空集合安全，避免逐行 selectOne 造成 N+1） */
    public Map<Long, UsageRecord> batchUsageMap(List<Reservation> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = list.stream().map(Reservation::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                        .in(UsageRecord::getReservationId, ids)).stream()
                .collect(Collectors.toMap(UsageRecord::getReservationId, Function.identity(), (a, b) -> a));
    }

    /** 预约实体 → 学生端 VO（教室展示字段来自批量预查 Map，避免 N+1） */
    public ReservationVO toReservationVO(Reservation r, Map<Long, Classroom> roomMap) {
        ReservationVO vo = new ReservationVO();
        vo.setId(r.getId());
        vo.setClassroomId(r.getClassroomId());
        vo.setReserveDate(r.getReserveDate());
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPeopleCount(r.getPeopleCount());
        vo.setPurpose(r.getPurpose());
        vo.setStatus(r.getStatus());
        vo.setAuditRemark(r.getAuditRemark());
        vo.setAuditTime(r.getAuditTime());
        vo.setCancelReason(r.getCancelReason());
        vo.setCancelTime(r.getCancelTime());
        vo.setCreateTime(r.getCreateTime());
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 管理端 VO（不含使用登记信息，等价于使用记录为空） */
    public ReservationManageVO toManageVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
        return toManageVO(r, userMap, roomMap, Collections.emptyMap());
    }

    /** 预约实体 → 管理端 VO（补全用户 + 教室 + 使用登记信息，来自批量预查 Map） */
    public ReservationManageVO toManageVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap,
                                          Map<Long, UsageRecord> usageMap) {
        ReservationManageVO vo = new ReservationManageVO();
        vo.setId(r.getId());
        vo.setUserId(r.getUserId());
        vo.setClassroomId(r.getClassroomId());
        vo.setReserveDate(r.getReserveDate());
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPeopleCount(r.getPeopleCount());
        vo.setPurpose(r.getPurpose());
        vo.setStatus(r.getStatus());
        vo.setAuditRemark(r.getAuditRemark());
        vo.setCancelReason(r.getCancelReason());
        vo.setCancelTime(r.getCancelTime());
        vo.setAuditorId(r.getAuditorId());
        vo.setAuditTime(r.getAuditTime());
        vo.setCreateTime(r.getCreateTime());
        // 使用登记信息（未登记的预约 actualPeople/useStatus 保持 null，前端展示「未登记」）
        UsageRecord usage = usageMap.get(r.getId());
        if (usage != null) {
            vo.setActualPeople(usage.getActualPeople());
            vo.setUseStatus(usage.getUseStatus());
        }
        SysUser user = userMap.get(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 导出 VO（不含使用登记信息，等价于使用记录为空） */
    public ReservationExportVO toExportVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
        return toExportVO(r, userMap, roomMap, Collections.emptyMap());
    }

    /** 预约实体 → 导出 VO（补全用户 + 教室 + 使用登记信息；时间统一字符串输出） */
    public ReservationExportVO toExportVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap,
                                          Map<Long, UsageRecord> usageMap) {
        ReservationExportVO vo = new ReservationExportVO();
        vo.setId(r.getId());
        vo.setReserveDate(String.valueOf(r.getReserveDate()));
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPurpose(r.getPurpose());
        vo.setStatusText(statusText(r.getStatus()));
        vo.setAuditRemark(r.getAuditRemark());
        vo.setAuditTime(TimeUtil.formatDateTime(r.getAuditTime()));
        vo.setCreateTime(TimeUtil.formatDateTime(r.getCreateTime()));
        // 取消原因 / 取消时间（需求文档 1.3：保留取消原因与取消时间，可条件查询导出）
        vo.setCancelReason(r.getCancelReason());
        vo.setCancelTime(TimeUtil.formatDateTime(r.getCancelTime()));
        // 使用情况 / 实际使用人数（需求文档 1.3：保留失约标记）
        UsageRecord usage = usageMap.get(r.getId());
        vo.setUseStatusText(useStatusText(usage == null ? null : usage.getUseStatus()));
        vo.setActualPeople(usage == null ? null : usage.getActualPeople());
        SysUser user = userMap.get(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 解析可选日期参数（空返回 null，非法抛 400） */
    public LocalDate parseDateOrNull(String date) {
        return StrUtil.isBlank(date) ? null : TimeUtil.parseDate(date);
    }

    /** 预约状态 → 导出展示文案（与前端状态标签口径一致：0-待审核，1-已通过，2-已驳回，3-已取消） */
    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case Constants.RES_STATUS_PENDING -> "待审核";
            case Constants.RES_STATUS_APPROVED -> "已通过";
            case Constants.RES_STATUS_REJECTED -> "已驳回";
            case Constants.RES_STATUS_CANCELED -> "已取消";
            default -> "未知";
        };
    }

    /** 使用状态 → 导出展示文案（1-正常使用，2-部分使用，3-失约；无使用记录为「未登记」） */
    private String useStatusText(Integer useStatus) {
        if (useStatus == null) {
            return "未登记";
        }
        return switch (useStatus) {
            case Constants.USE_STATUS_NORMAL -> "正常使用";
            case Constants.USE_STATUS_PARTIAL -> "部分使用";
            case Constants.USE_STATUS_NO_SHOW -> "失约";
            default -> "未知";
        };
    }
}
