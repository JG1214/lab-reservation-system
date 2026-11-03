package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.RedisProperties;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UsageRecord;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UsageRecordMapper;
import com.example.reservation.service.StatsService;
import com.example.reservation.vo.ActualUsageVO;
import com.example.reservation.vo.OverviewVO;
import com.example.reservation.vo.TimeDistributionVO;
import com.example.reservation.vo.TrendVO;
import com.example.reservation.vo.UsageRateVO;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计业务实现（R5 数据看板，只读）
 * 口径（写进交付说明，测试按此断言与库中数据一致）：
 *  1. 使用率 = 区间内该教室【已通过】预约占用小时数 ÷（区间天数 × 每日可预约时长 14h）× 100，保留 1 位小数；
 *  2. 月度趋势 = 按预约日期归属自然月统计区间内【全部状态】预约条数；
 *  3. 时段分布 = 按开始时间分桶（08-10/10-12/14-16/16-18/19-21/其他）统计【已通过】预约占比。
 * 日期区间缺省逻辑：全缺省 = 近 30 天；仅一个缺省 = 以另一个为基准补 29 天；跨度上限 366 天。
 *
 * @author reservation-team
 */
@Service
public class StatsServiceImpl implements StatsService {

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UsageRecordMapper usageRecordMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private RedisProperties redisProperties;

    /**
     * 管理端首页数据概览（4 次 COUNT）
     *
     * <p>此处<b>不引入缓存</b>：① 计数语句命中主键/索引，开销远低于看板区间聚合；
     * ② 用户总数在「注册」后需即时体现，而注册不推进看板缓存代次，若缓存会出现
     * ≤TTL 的滞后（答辩现场注册后立即查看管理员首页会看到旧数字）。
     */
    @Override
    public OverviewVO overview() {
        LocalDate today = LocalDate.now();
        OverviewVO vo = new OverviewVO();
        // 今日预约：预约日期为今日，全部状态（与月度趋势同口径）
        vo.setTodayReservationCount(reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getReserveDate, today)));
        // 待审核：状态待审核(0)，不限日期（审核工作量口径）
        vo.setPendingAuditCount(reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Constants.RES_STATUS_PENDING)));
        // 教室总数（含已停用教室）
        vo.setClassroomCount(classroomMapper.selectCount(new LambdaQueryWrapper<Classroom>()));
        // 用户总数（含管理员与已禁用账号）
        vo.setUserCount(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()));
        // 待登记：已通过但尚未登记使用记录的预约条数（使用登记工作量口径，V4.0 新增）
        Long recorded = usageRecordMapper.selectCount(new LambdaQueryWrapper<UsageRecord>());
        Long approved = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
        vo.setPendingUsageCount(Math.max(0L, approved - (recorded == null ? 0L : recorded)));
        // 今日实际使用人数：使用日期为今日且非失约的记录，实际人数合计（V4.0 新增）
        List<UsageRecord> todayRecords = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getUseDate, today)
                .ne(UsageRecord::getUseStatus, Constants.USE_STATUS_NO_SHOW));
        vo.setTodayActualPeople(todayRecords.stream()
                .mapToLong(r -> r.getActualPeople() == null ? 0L : r.getActualPeople())
                .sum());
        return vo;
    }

    /**
     * 实际使用统计（需求设计文档 V4.0 1.3 管理端「数据统计」第 4 项）
     *
     * <p>与 usageRate 的口径差异（两者并列展示，互不覆盖）：
     * 使用率衡量「排期被预约占用了多少」，本方法衡量「预约最终被真实使用了多少」。
     * 实际使用小时 = 该条预约的窗口内分钟数 × (实际人数 ÷ 预约人数)，
     * 全员到场时退化为预约占用小时，部分使用按人数折算，失约折算为 0。
     */
    @Override
    public List<ActualUsageVO> actualUsage(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        String cacheKey = statsCacheKey("actual-usage", range);
        List<ActualUsageVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }

        // 区间内已登记的使用记录（含正常/部分/失约），以登记日期归属区间
        List<UsageRecord> records = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .ge(UsageRecord::getUseDate, range.start)
                .le(UsageRecord::getUseDate, range.end));

        // 记录 → 预约原文（取预约人数与时段，用于折算实际使用小时）
        Map<Long, Reservation> reservationMap = new HashMap<>();
        if (!records.isEmpty()) {
            List<Long> reservationIds = records.stream().map(UsageRecord::getReservationId).distinct().toList();
            reservationMap = reservationMapper.selectBatchIds(reservationIds).stream()
                    .collect(Collectors.toMap(Reservation::getId, r -> r, (a, b) -> a));
        }

        // 按教室聚合：条数/人数/折算分钟
        Map<Long, ActualAgg> aggByClassroom = new HashMap<>();
        for (UsageRecord record : records) {
            ActualAgg agg = aggByClassroom.computeIfAbsent(record.getClassroomId(), k -> new ActualAgg());
            agg.recordedCount++;
            agg.actualPeople += record.getActualPeople() == null ? 0 : record.getActualPeople();
            Integer useStatus = record.getUseStatus();
            if (useStatus != null && useStatus == Constants.USE_STATUS_NO_SHOW) {
                agg.noShowCount++;
            } else if (useStatus != null && useStatus == Constants.USE_STATUS_PARTIAL) {
                agg.partialCount++;
            } else if (useStatus != null && useStatus == Constants.USE_STATUS_NORMAL) {
                agg.normalCount++;
            }
            Reservation reservation = reservationMap.get(record.getReservationId());
            if (reservation == null) {
                continue;
            }
            int planned = reservation.getPeopleCount() == null ? 1 : reservation.getPeopleCount();
            agg.plannedPeople += planned;
            long occupancyMinutes = clippedMinutes(reservation);
            int actual = record.getActualPeople() == null ? 0 : record.getActualPeople();
            // 按人数折算：全员到场 = 全额占用；部分使用按比例；失约 = 0
            agg.actualMinutes += planned <= 0 ? 0 : Math.round(occupancyMinutes * (actual * 1.0 / planned));
        }

        // 区间内非驳回预约（待审核 0 / 已通过 1 / 已取消 3）：一次查询同时派生失约率分母与取消率分子分母
        List<Reservation> nonRejected = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .in(Reservation::getStatus, Constants.RES_STATUS_PENDING,
                        Constants.RES_STATUS_APPROVED, Constants.RES_STATUS_CANCELED)
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end));
        // 失约率分母：区间内各教室已通过的预约条数（未登记的失约同样计入分母，避免"不登记就一直不计失约"）
        Map<Long, Long> approvedCountByClassroom = nonRejected.stream()
                .filter(r -> r.getStatus() == Constants.RES_STATUS_APPROVED)
                .collect(Collectors.groupingBy(Reservation::getClassroomId, Collectors.counting()));
        // 取消率（需求文档 V4.1 2.5 第 6 条）：分子=区间内已取消条数，分母=区间内非驳回条数（驳回不计入分母）
        Map<Long, Long> cancelledCountByClassroom = nonRejected.stream()
                .filter(r -> r.getStatus() == Constants.RES_STATUS_CANCELED)
                .collect(Collectors.groupingBy(Reservation::getClassroomId, Collectors.counting()));
        Map<Long, Long> nonRejectedCountByClassroom = nonRejected.stream()
                .collect(Collectors.groupingBy(Reservation::getClassroomId, Collectors.counting()));

        long days = ChronoUnit.DAYS.between(range.start, range.end) + 1;
        double denominatorHours = days * Constants.DAILY_AVAILABLE_HOURS;

        List<Classroom> rooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>());
        List<ActualUsageVO> result = new ArrayList<>(rooms.size());
        for (Classroom room : rooms) {
            ActualAgg agg = aggByClassroom.get(room.getId());
            ActualUsageVO vo = new ActualUsageVO();
            vo.setClassroomId(room.getId());
            vo.setName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
            vo.setType(room.getType());
            vo.setCapacity(room.getCapacity());
            if (agg == null) {
                vo.setRecordedCount(0L);
                vo.setNormalCount(0L);
                vo.setPartialCount(0L);
                vo.setNoShowCount(0L);
                vo.setPlannedPeople(0L);
                vo.setActualPeople(0L);
                vo.setActualHours(0.0);
                vo.setActualUsageRate(0.0);
                vo.setNoShowRate(0.0);
            } else {
                // 折算小时先按「单日 14h」封顶，保证实际使用率恒 ≤ 100
                double actualHours = Math.min(agg.actualMinutes, Constants.DAILY_AVAILABLE_HOURS * 60L * days) / 60.0;
                vo.setRecordedCount(agg.recordedCount);
                vo.setNormalCount(agg.normalCount);
                vo.setPartialCount(agg.partialCount);
                vo.setNoShowCount(agg.noShowCount);
                vo.setPlannedPeople(agg.plannedPeople);
                vo.setActualPeople(agg.actualPeople);
                vo.setActualHours(round1(actualHours));
                vo.setActualUsageRate(round1(denominatorHours == 0 ? 0 : actualHours / denominatorHours * 100));
                // 失约率 = 失约记录数 / 已通过预约数，分母取该教室区间内已通过预约的条数（与接口清单 6.2 口径一致）
                long approvedCount = approvedCountByClassroom.getOrDefault(room.getId(), 0L);
                vo.setNoShowRate(round1(approvedCount == 0 ? 0 : agg.noShowCount * 100.0 / approvedCount));
            }
            // 取消率与「是否有使用登记」无关，两个分支都要填，否则无登记记录的实验室会漏统计
            long cancelledCount = cancelledCountByClassroom.getOrDefault(room.getId(), 0L);
            long nonRejectedCount = nonRejectedCountByClassroom.getOrDefault(room.getId(), 0L);
            vo.setCancelledCount(cancelledCount);
            vo.setCancelRate(round1(nonRejectedCount == 0 ? 0 : cancelledCount * 100.0 / nonRejectedCount));
            result.add(vo);
        }
        result.sort(Comparator.comparing(ActualUsageVO::getRecordedCount, Comparator.reverseOrder())
                .thenComparing(ActualUsageVO::getActualUsageRate, Comparator.reverseOrder())
                .thenComparing(ActualUsageVO::getClassroomId));
        redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
        return result;
    }

    @Override
    public List<UsageRateVO> usageRate(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）；Redis 异常时 getObject 返回 null 自动回源
        String cacheKey = statsCacheKey("usage-rate", range);
        List<UsageRateVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【已通过】预约（使用率口径：实际占用）
        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));

        // H3 修复：分子与分母同口径——占用分钟先裁剪到可预约窗口（08:00-22:00），再按天封顶 14h。
        // 此前直接累加 start-end 分钟数，提交侧若产生窗口外/超长记录（历史脏数据或绕过校验），
        // 使用率可超 100%；现以（教室, 日期）为粒度裁剪+封顶后再汇总，保证分子 ≤ 分母恒成立
        Map<Long, Map<LocalDate, Long>> minutesByClassroomAndDate = approved.stream().collect(Collectors.groupingBy(
                Reservation::getClassroomId,
                Collectors.groupingBy(Reservation::getReserveDate,
                        Collectors.summingLong(this::clippedMinutes))));

        // 教室 ID → 占用小时数（分钟转小时，保留 1 位）
        Map<Long, Double> hoursByClassroom = new HashMap<>();
        for (Map.Entry<Long, Map<LocalDate, Long>> entry : minutesByClassroomAndDate.entrySet()) {
            double totalMinutes = 0;
            for (long minutes : entry.getValue().values()) {
                // 每日封顶：单教室单日最多计 14h（= 分母每日可预约时长，同口径）
                totalMinutes += Math.min(minutes, Constants.DAILY_AVAILABLE_HOURS * 60L);
            }
            hoursByClassroom.put(entry.getKey(), totalMinutes / 60.0);
        }

        long days = ChronoUnit.DAYS.between(range.start, range.end) + 1;
        double denominatorHours = days * Constants.DAILY_AVAILABLE_HOURS;

        List<Classroom> rooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>());
        List<UsageRateVO> result = new ArrayList<>(rooms.size());
        for (Classroom room : rooms) {
            double hours = hoursByClassroom.getOrDefault(room.getId(), 0.0);
            UsageRateVO vo = new UsageRateVO();
            vo.setClassroomId(room.getId());
            vo.setName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
            vo.setType(room.getType());
            vo.setCapacity(room.getCapacity());
            vo.setApprovedHours(round1(hours));
            // 使用率 = 占用小时 /（天数 × 每日可预约时长），超 100 不封顶（口径一致即可复算）
            vo.setUsageRate(round1(denominatorHours == 0 ? 0 : hours / denominatorHours * 100));
            result.add(vo);
        }
        // 排行：使用率倒序 → 占用小时倒序 → 教室 ID 升序（稳定可断言）
        result.sort(Comparator.comparing(UsageRateVO::getUsageRate, Comparator.reverseOrder())
                .thenComparing(UsageRateVO::getApprovedHours, Comparator.reverseOrder())
                .thenComparing(UsageRateVO::getClassroomId));
        // 回填缓存（短 TTL；预约/教室变更时由业务写操作主动失效，保证一致性）
        redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
        return result;
    }

    @Override
    public List<TrendVO> trend(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）
        String cacheKey = statsCacheKey("trend", range);
        List<TrendVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【全部状态】预约（趋势口径：预约提交活跃度）
        List<Reservation> all = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end));

        // 按预约日期归属自然月分组计数，月份升序输出
        Map<YearMonth, Long> byMonth = all.stream().collect(Collectors.groupingBy(
                r -> YearMonth.from(r.getReserveDate()), Collectors.counting()));
        return byMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    TrendVO vo = new TrendVO();
                    vo.setMonth(e.getKey().toString());
                    vo.setCount(e.getValue());
                    return vo;
                })
                .collect(Collectors.collectingAndThen(Collectors.toList(), result -> {
                    // 回填缓存（短 TTL；预约/教室变更时主动失效）
                    redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
                    return result;
                }));
    }

    @Override
    public List<TimeDistributionVO> timeDistribution(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）
        String cacheKey = statsCacheKey("time-distribution", range);
        List<TimeDistributionVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【已通过】预约（时段分布口径：实际占用时段）
        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));

        // 分桶计数（保持桶顺序：前 5 桶 + 「其他」兜底）
        long[] counts = new long[Constants.TIME_SLOT_BUCKET_COUNT];
        long total = 0;
        for (Reservation r : approved) {
            int idx = slotIndex(r.getStartTime());
            counts[idx]++;
            total++;
        }

        List<TimeDistributionVO> result = new ArrayList<>(Constants.TIME_SLOT_BUCKET_COUNT);
        for (int i = 0; i < Constants.TIME_SLOT_BUCKET_COUNT; i++) {
            TimeDistributionVO vo = new TimeDistributionVO();
            vo.setSlot(Constants.TIME_SLOT_LABELS[i]);
            vo.setCount(counts[i]);
            vo.setPercentage(round1(total == 0 ? 0 : counts[i] * 100.0 / total));
            result.add(vo);
        }
        // 回填缓存（短 TTL；预约/教室变更时主动失效）
        redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
        return result;
    }

    /* ==================== 私有工具方法 ==================== */

    /**
     * 组装看板统计缓存 Key：cache:stats:g{代次}:{接口名}:{开始日期}:{结束日期}
     * （区间经 resolveRange 归一化，相同区间共享同一份缓存）
     *
     * <p>{@code g{代次}} 为运行时代次（{@link RedisCache#STATS_GEN_KEY}）：预约/教室变更时 INCR 即让本族缓存逻辑失效，
     * 替代原「SCAN 遍历删除」——失效开销 O(1)，旧代次 Key 由 TTL 自然过期。
     */
    private String statsCacheKey(String api, DateRange range) {
        return RedisCache.STATS_KEY_PREFIX
                + "g" + redisCache.currentGeneration(RedisCache.STATS_GEN_KEY) + ":"
                + api + ":" + range.start + ":" + range.end;
    }

    /** 开始时间 → 时段桶下标（0-4 对应前 5 桶，5 为「其他」；M14 修复：区间取自 Constants 单一来源，不再双份维护） */
    private int slotIndex(LocalTime start) {
        LocalTime[][] ranges = Constants.TIME_SLOT_RANGES;
        for (int i = 0; i < ranges.length; i++) {
            // 桶范围：[左边界, 右边界)
            if (!start.isBefore(ranges[i][0]) && start.isBefore(ranges[i][1])) {
                return i;
            }
        }
        return Constants.TIME_SLOT_BUCKET_COUNT - 1;
    }

    /**
     * 单条预约的窗口内占用分钟数（H3 修复）：裁剪到每日可预约窗口 08:00-22:00 后取正值；
     * 窗口外或空值返回 0（历史脏数据/异常数据不会污染分子）
     */
    private long clippedMinutes(Reservation r) {
        LocalTime start = r.getStartTime();
        LocalTime end = r.getEndTime();
        if (start == null || end == null) {
            return 0;
        }
        LocalTime clipStart = start.isBefore(Constants.DAILY_SLOT_START) ? Constants.DAILY_SLOT_START : start;
        LocalTime clipEnd = end.isAfter(Constants.DAILY_SLOT_END) ? Constants.DAILY_SLOT_END : end;
        if (!clipStart.isBefore(clipEnd)) {
            return 0;
        }
        return ChronoUnit.MINUTES.between(clipStart, clipEnd);
    }

    /** 日期区间解析：校验格式/先后/跨度；缺省按近 30 天补齐 */
    private DateRange resolveRange(String startDate, String endDate) {
        LocalDate start = parseDateOrNull(startDate);
        LocalDate end = parseDateOrNull(endDate);
        LocalDate today = LocalDate.now();
        if (start == null && end == null) {
            end = today;
            start = end.minusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        } else if (start == null) {
            start = end.minusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        } else if (end == null) {
            end = start.plusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        }
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > Constants.STATS_MAX_DAYS) {
            throw new BusinessException("统计区间跨度不能超过 366 天");
        }
        return new DateRange(start, end);
    }

    /** 解析可选日期参数（空返回 null，非法抛 400） */
    private LocalDate parseDateOrNull(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return TimeUtil.parseDate(date);
    }

    /** 数值保留 1 位小数（四舍五入） */
    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 单教室实际使用聚合中间结果 */
    private static final class ActualAgg {
        private long recordedCount;
        private long normalCount;
        private long partialCount;
        private long noShowCount;
        private long plannedPeople;
        private long actualPeople;
        private long actualMinutes;
    }

    /** 日期区间（开始/结束均含） */
    private static final class DateRange {
        private final LocalDate start;
        private final LocalDate end;

        private DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }
}
