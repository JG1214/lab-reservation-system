<template>
  <div class="occupancy-page page-container">
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">实验室占用总览</h1>
        <p class="page-tip">按日期查看各实验室已通过预约的时段占用情况，便于安排课程与实验。</p>
      </div>
      <div class="head-actions">
        <el-date-picker
          v-model="queryDate"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          aria-label="查询日期"
          @change="loadOccupancy"
        />
      </div>
    </header>

    <div class="filter-bar">
      <el-select v-model="building" placeholder="全部楼栋" clearable aria-label="楼栋筛选" @change="loadOccupancy">
        <el-option v-for="b in buildingOptions" :key="b" :label="b" :value="b" />
      </el-select>
      <el-select v-model="type" placeholder="全部类型" clearable aria-label="类型筛选" @change="loadOccupancy">
        <el-option label="普通教室" :value="1" />
        <el-option label="实验室" :value="2" />
        <el-option label="机房" :value="3" />
      </el-select>
      <span class="result-count">共 {{ list.length }} 间</span>
    </div>

    <el-alert
      v-if="!loading && list.length && !anySlot"
      class="slot-alert"
      type="info"
      :closable="false"
      show-icon
      title="该日期没有实验室配置开放时段"
      description="开放时段按「开放星期」生效，切换到有开放配置的日期即可查看占用情况。"
    />

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-empty v-else-if="!list.length" description="该日期暂无可查看的实验室">
      <template #image>
        <el-icon :size="48" class="empty-icon"><Grid /></el-icon>
      </template>
      <p class="empty-tip">可切换日期或放宽筛选条件后重试。</p>
    </el-empty>

    <div v-else class="occupancy-grid">
      <article v-for="item in list" :key="item.id" class="occupancy-card">
        <header class="card-head">
          <h2 class="card-title">{{ item.name }}</h2>
          <el-tag :type="occupancyTagType(item)" size="small" effect="plain">
            占用 {{ item.occupiedCount }}/{{ item.slotCount }} 段
          </el-tag>
        </header>
        <p class="card-meta">
          <span>{{ item.building }}{{ item.roomNo ? ' · ' + item.roomNo : '' }}</span>
          <span>可容纳 {{ item.capacity }} 人</span>
        </p>
        <div class="slot-track" role="list" aria-label="时段占用">
          <div
            v-for="slot in item.slots"
            :key="slot.slotId"
            class="slot-seg"
            :class="'is-' + slot.level"
            role="listitem"
            :title="slotTitle(slot)"
          >
            <span class="seg-label">{{ slot.openTime }}–{{ slot.closeTime }}</span>
            <span class="seg-sub">{{ slot.level === 'full' ? slot.booked : '上限 ' + slot.capacity + ' 人' }}</span>
          </div>
        </div>
        <p v-if="!item.slots || !item.slots.length" class="card-empty">当日无开放时段配置</p>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import dayjs from 'dayjs'
import { Grid } from '@element-plus/icons-vue'
import { listClassrooms } from '@/api/classroom'
import { listClassroomSlots } from '@/api/slot'

const loading = ref(false)
const list = ref([])
const buildingOptions = ref([])
// 默认查询当天（用 dayjs 取本地日期，toISOString 走 UTC 会在凌晨 8 点前串到前一天）
const queryDate = ref(dayjs().format('YYYY-MM-DD'))
const building = ref('')
const type = ref(null)

const formatClock = (t) => String(t || '').slice(0, 5)

/** 单间实验室的时段占用汇总：occupiedCount = 当日已被已通过预约覆盖的开放时段数 */
function summarise(classroom) {
  const occupied = classroom.occupiedSlots || []
  const windows = classroom.slots || []
  const slots = windows.map((slot) => {
    const open = formatClock(slot.openTime)
    const close = formatClock(slot.closeTime)
    const start = occupied.find((o) => o.startTime < close && o.endTime > open)
    return {
      slotId: slot.id,
      openTime: open,
      closeTime: close,
      capacity: slot.slotCapacity,
      level: start ? 'full' : 'free',
      booked: start ? start.startTime + '–' + start.endTime : ''
    }
  })
  return {
    ...classroom,
    slots,
    slotCount: slots.length,
    occupiedCount: slots.filter((s) => s.level === 'full').length
  }
}

/** 当日所有实验室都查不到生效时段（如周末未配置开放星期）时给出说明，避免页面看起来像没数据 */
const anySlot = computed(() => list.value.some((item) => item.slotCount > 0))

/** 标签色：被占用时段占比越高越醒目 */
function occupancyTagType(item) {
  if (!item.slotCount) return 'info'
  const ratio = item.occupiedCount / item.slotCount
  if (ratio >= 0.8) return 'danger'
  if (ratio >= 0.4) return 'warning'
  return 'success'
}

function slotTitle(slot) {
  const base = slot.openTime + '–' + slot.closeTime
  if (slot.level === 'full') return base + ' 已被 ' + slot.booked + ' 的预约占用'
  return base + ' 空闲，上限 ' + slot.capacity + ' 人'
}

/** 加载占用总览数据：日期 + 楼栋 + 类型筛选 */
async function loadOccupancy() {
  loading.value = true
  try {
    // request.js 拦截器返回的是完整 Result（{code,message,data}），业务数据在 res.data 里
    const res = await listClassrooms({
      date: queryDate.value,
      building: building.value || undefined,
      type: type.value ?? undefined,
      page: 1,
      size: 100
    })
    // 教室列表接口分页返回，总览页一次取满
    const rows = res?.data?.records || []
    // 并行补齐每间实验室的开放时段（/api/slot/classroom/{id}?date=），同样从 Result.data 取数组
    const slotsOf = await Promise.all(
      rows.map((row) =>
        listClassroomSlots(row.id, queryDate.value)
          .then((r) => r?.data || [])
          .catch(() => [])
      )
    )
    list.value = rows.map((row, i) => summarise({ ...row, slots: slotsOf[i] }))
    if (!buildingOptions.value.length) {
      buildingOptions.value = [...new Set(rows.map((i) => i.building).filter(Boolean))]
    }
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadOccupancy)
</script>

<style scoped>
/* 限宽、页头与水平留白统一走全局：.page-container / .page-head / .head-left / .page-tip */
.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  flex-wrap: wrap;
  margin-bottom: var(--space-md);
}

.filter-bar .el-select {
  width: 160px;
}

.result-count {
  font-size: 13px;
  color: var(--text-regular);
}

.slot-alert {
  margin-bottom: var(--space-md);
}

.occupancy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-md);
}

.occupancy-card {
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  background: var(--bg-card);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: var(--space-sm);
}

.card-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.card-meta {
  display: flex;
  gap: var(--space-lg);
  margin: 0 0 var(--space-md);
  font-size: 12px;
  color: var(--text-regular);
}

.slot-track {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.slot-seg {
  flex: 1 1 0;
  min-width: 54px;
  padding: 6px 4px;
  border-radius: var(--radius-md);
  text-align: center;
  font-size: 11px;
  line-height: 1.3;
}

.slot-seg.is-free {
  background: var(--brand-success-light);
  color: var(--brand-success);
}

.slot-seg.is-tight {
  background: var(--brand-warning-light);
  color: var(--brand-warning);
}

.slot-seg.is-full {
  background: var(--brand-danger-light);
  color: var(--brand-danger);
}

.slot-seg {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.seg-label {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.seg-sub {
  font-size: 10px;
  opacity: 0.85;
}

.card-empty {
  margin: 0;
  font-size: 12px;
  color: var(--text-placeholder);
}

.empty-icon {
  color: var(--text-placeholder);
}

.empty-tip {
  margin: var(--space-sm) 0 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

/* ---------- 响应式：1200 降 2 列，768 降单列 ---------- */
@media (max-width: 1200px) {
  .occupancy-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .occupancy-card {
    padding: var(--space-md);
  }
}

@media (max-width: 768px) {
  .occupancy-grid {
    grid-template-columns: 1fr;
  }
  .filter-bar .el-select {
    width: 100%;
  }
}
</style>
