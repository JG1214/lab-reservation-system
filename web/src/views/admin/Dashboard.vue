<template>
  <div class="page-container-wide">
    <!-- 顶部：标题 + 时间范围筛选 -->
    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <span class="page-title">数据看板</span>
        <el-radio-group v-model="quickRange" size="small" @change="handleQuickRange">
          <el-radio-button value="7">近 7 天</el-radio-button>
          <el-radio-button value="30">近 30 天</el-radio-button>
          <el-radio-button value="90">近 90 天</el-radio-button>
          <el-radio-button value="year">本年</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
          @change="handleDateRangeChange"
        />
        <el-button type="primary" size="small" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </el-card>

    <!-- 四图表（需求文档 V4.0 2.4 第 14 条）：使用率排行 / 热门时段 / 月度趋势 / 实际使用人数 -->
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never" class="chart-card">
          <div class="chart-title">教室使用率排行</div>
          <div class="chart-sub">已通过预约占用小时 / 区间可预约小时</div>
          <div ref="usageRateEl" class="chart-box" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="chart-card">
          <div class="chart-title">热门时段分布</div>
          <div class="chart-sub">已通过预约按开始时段</div>
          <div ref="timeDistEl" class="chart-box" />
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="chart-card">
      <div class="chart-title">月度预约趋势</div>
      <div class="chart-sub">全部状态预约条数</div>
      <div ref="trendEl" class="chart-box chart-box-tall" />
    </el-card>

    <!-- 第四图：实际使用人数统计（需求文档 V4.0 2.4 第 14 条「实际使用人数统计」） -->
    <el-card shadow="never" class="chart-card">
      <div class="chart-title">实际使用人数统计</div>
      <div class="chart-sub">实到人数 / 预约人数，仅统计已登记使用的预约</div>
      <div ref="actualPeopleEl" class="chart-box chart-box-mid" />
    </el-card>

    <!-- 实际使用明细：登记数 / 失约率 / 实际使用率（口径见需求文档 V4.0 模块六） -->
    <el-card shadow="never" class="chart-card">
      <div class="chart-title">实验室实际使用情况</div>
      <div class="chart-sub">按登记记录统计，实际使用率 = 实际人时 / 区间可预约人时</div>
      <el-table :data="actualUsageList" empty-text="暂无使用登记数据" size="small">
        <el-table-column label="实验室" min-width="180">
          <template #default="{ row }">
            {{ row.building }} {{ row.roomNo }}
            <span class="row-muted">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="recordedCount" label="已登记" width="88" align="right" />
        <el-table-column label="正常 / 部分 / 失约" width="168" align="right">
          <template #default="{ row }">
            <span class="count-normal">{{ row.normalCount }}</span>
            <span class="count-sep">/</span>
            <span class="count-partial">{{ row.partialCount }}</span>
            <span class="count-sep">/</span>
            <span class="count-noshow">{{ row.noShowCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实到 / 预约人数" width="148" align="right">
          <template #default="{ row }">{{ row.actualPeople }} / {{ row.plannedPeople }}</template>
        </el-table-column>
        <el-table-column prop="actualHours" label="实际人时" width="104" align="right" />
        <el-table-column label="实际使用率" width="112" align="right">
          <template #default="{ row }">{{ row.actualUsageRate }}%</template>
        </el-table-column>
        <el-table-column label="失约率" width="96" align="right">
          <template #default="{ row }">{{ row.noShowRate }}%</template>
        </el-table-column>
        <el-table-column label="取消率" width="96" align="right">
          <template #default="{ row }">{{ row.cancelRate }}%</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
// ECharts 按需引入（R6 联调优化：避免全量包约 1MB 打入 Dashboard chunk，依赖版本不变）
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, GraphicComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import dayjs from 'dayjs'
import { getUsageRate, getTrend, getTimeDistribution, getActualUsage } from '@/api/stats'

// 为满足 multi-word 规则并便于 devtools 辨识
defineOptions({ name: 'DashboardView' })

// 注册看板用到的图表类型与组件（柱状图/折线图/饼图 + 提示框/网格/图例 + 空数据提示 graphic 文本 + Canvas 渲染）
echarts.use([BarChart, LineChart, PieChart, TooltipComponent, GridComponent, LegendComponent, GraphicComponent, CanvasRenderer])

/**
 * 图表配色（Canvas 无法读取 CSS 变量，此处按值对齐 main.css 的飞书 Token）
 * 轴/网格线用描边与填充色，文字用中性色，数据色只用品牌蓝与飞书功能色 600 级
 */
const CHART = {
  primary: '#1456f0', // --brand-primary
  primaryArea: 'rgba(20, 86, 240, 0.10)', // 同色系低透明面积，无渐变
  axisLine: '#dee0e3', // --border-color-light
  splitLine: '#eff0f1', // --bg-fill
  axisText: '#646a73', // --text-regular
  labelText: '#1f2329', // --text-primary
  emptyText: '#8f959e', // --text-placeholder
  pieGap: '#ffffff', // --bg-card：饼图分段间隙
  palette: ['#1456f0', '#1a7526', '#a44904', '#c02a26', '#0e9594', '#646a73', '#8f959e']
}

const loading = ref(false)
const quickRange = ref('30')
const dateRange = ref([dayjs().subtract(29, 'day').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])

const actualUsageList = ref([])

const usageRateEl = ref(null)
const trendEl = ref(null)
const timeDistEl = ref(null)
const actualPeopleEl = ref(null)

let usageChart = null
let trendChart = null
let timeDistChart = null
let actualPeopleChart = null

/** 当前区间参数（YYYY-MM-DD） */
function rangeParams() {
  const [start, end] = dateRange.value || []
  return start && end ? { startDate: start, endDate: end } : {}
}

/** 快捷范围选择：近 7/30/90 天、本年 */
function handleQuickRange(key) {
  const today = dayjs()
  if (key === 'year') {
    dateRange.value = [today.startOf('year').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
  } else {
    const days = Number(key)
    dateRange.value = [today.subtract(days - 1, 'day').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
  }
  loadAll()
}

/** 自定义日期范围变化 */
function handleDateRangeChange() {
  if (dateRange.value && dateRange.value.length === 2) {
    quickRange.value = ''
    loadAll()
  }
}

/** 三图数据均来自后端只读统计接口（禁止前端伪造），时间筛选统一作用三图 */
async function loadAll() {
  loading.value = true
  try {
    const params = rangeParams()
    const [usageRes, trendRes, distRes, actualRes] = await Promise.all([
      getUsageRate(params),
      getTrend(params),
      getTimeDistribution(params),
      getActualUsage(params)
    ])
    renderUsageRate(usageRes.data || [])
    renderTrend(trendRes.data || [])
    renderTimeDistribution(distRes.data || [])
    actualUsageList.value = actualRes.data || []
    renderActualPeople(actualUsageList.value)
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 空数据提示（ECharts graphic 文本占位；数据为空时显示，有数据时 setOption(notMerge=true) 整表替换自动清除） */
function emptyGraphic(text) {
  return [
    {
      type: 'text',
      left: 'center',
      top: 'middle',
      style: {
        text,
        fontSize: 12,
        fill: CHART.emptyText
      }
    }
  ]
}

/**
 * 柱状图：实际使用人数统计（需求文档 V4.0 2.4 第 14 条第四张图）
 * 只取有登记记录的实验室；两色并列——实到人数用品牌蓝、预约人数用中性灰，避免双主色打架
 */
function renderActualPeople(list) {
  const rows = list.filter((r) => r.recordedCount > 0)
  if (!rows.length) {
    actualPeopleChart.setOption({ graphic: emptyGraphic('暂无使用登记数据') }, true)
    return
  }
  actualPeopleChart.setOption(
    {
      tooltip: {
        trigger: 'axis',
        formatter(params) {
          const item = rows[params[0].dataIndex]
          if (!item) {
            return ''
          }
          return `${item.name}（${item.roomNo}）<br/>实到 ${item.actualPeople} 人 / 预约 ${item.plannedPeople} 人<br/>已登记 ${item.recordedCount} 条（正常 ${item.normalCount} / 部分 ${item.partialCount} / 失约 ${item.noShowCount}）`
        }
      },
      legend: { top: 0, right: 0, fontSize: 12, textStyle: { color: CHART.axisText } },
      grid: { left: 48, right: 24, top: 44, bottom: 72 },
      xAxis: {
        type: 'category',
        data: rows.map((r) => r.name),
        axisLabel: { rotate: 35, fontSize: 12, color: CHART.axisText },
        axisLine: { lineStyle: { color: CHART.axisLine } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '人数',
        minInterval: 1,
        nameTextStyle: { color: CHART.emptyText },
        axisLabel: { fontSize: 12, color: CHART.axisText },
        splitLine: { lineStyle: { color: CHART.splitLine } }
      },
      series: [
        {
          name: '实到人数',
          type: 'bar',
          data: rows.map((r) => r.actualPeople),
          barMaxWidth: 24,
          itemStyle: { borderRadius: [4, 4, 0, 0], color: CHART.primary },
          label: { show: true, position: 'top', fontSize: 12, color: CHART.labelText, fontWeight: 500 }
        },
        {
          name: '预约人数',
          type: 'bar',
          data: rows.map((r) => r.plannedPeople),
          barMaxWidth: 24,
          itemStyle: { borderRadius: [4, 4, 0, 0], color: CHART.axisLine }
        }
      ]
    },
    true
  )
}

/** 柱状图：教室使用率排行（按使用率倒序，后端已排序；扁平纯色柱 + 顶部标签） */
function renderUsageRate(list) {
  if (!list.length) {
    usageChart.setOption({ graphic: emptyGraphic('暂无教室使用率数据') }, true)
    return
  }
  const names = list.map((r) => r.name)
  const values = list.map((r) => r.usageRate)
  usageChart.setOption(
    {
      tooltip: {
        trigger: 'axis',
        formatter(params) {
          const p = params[0]
          const item = list[p.dataIndex]
          if (!item) {
            return ''
          }
          return `${item.name}（${item.roomNo}）<br/>使用率：${item.usageRate}%<br/>占用时长：${item.approvedHours} 小时`
        }
      },
      grid: { left: 48, right: 24, top: 36, bottom: 80 },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: { rotate: 35, fontSize: 12, color: CHART.axisText },
        axisLine: { lineStyle: { color: CHART.axisLine } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '使用率（%）',
        nameTextStyle: { color: CHART.emptyText },
        axisLabel: { formatter: '{value}%', fontSize: 12, color: CHART.axisText },
        splitLine: { lineStyle: { color: CHART.splitLine } }
      },
      series: [
        {
          type: 'bar',
          data: values,
          barMaxWidth: 32,
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: CHART.primary
          },
          label: {
            show: true,
            position: 'top',
            formatter: (p) => `${p.value}%`,
            fontSize: 12,
            color: CHART.labelText,
            fontWeight: 500
          }
        }
      ]
    },
    true
  )
}

/** 折线图：月度预约趋势（主色线 + 同色系低透明面积，无渐变） */
function renderTrend(list) {
  if (!list.length) {
    trendChart.setOption({ graphic: emptyGraphic('暂无预约趋势数据') }, true)
    return
  }
  trendChart.setOption(
    {
      tooltip: { trigger: 'axis' },
      grid: { left: 48, right: 24, top: 36, bottom: 40 },
      xAxis: {
        type: 'category',
        data: list.map((r) => r.month),
        axisLabel: { fontSize: 12, color: CHART.axisText },
        axisLine: { lineStyle: { color: CHART.axisLine } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '预约条数',
        minInterval: 1,
        nameTextStyle: { color: CHART.emptyText },
        axisLabel: { fontSize: 12, color: CHART.axisText },
        splitLine: { lineStyle: { color: CHART.splitLine } }
      },
      series: [
        {
          type: 'line',
          data: list.map((r) => r.count),
          smooth: true,
          symbolSize: 8,
          itemStyle: { color: CHART.primary },
          lineStyle: { width: 2, color: CHART.primary },
          areaStyle: {
            color: CHART.primaryArea
          },
          label: { show: true, position: 'top', fontSize: 12, color: CHART.labelText, fontWeight: 500 }
        }
      ]
    },
    true
  )
}

/** 饼图：热门时段分布（占比口径与后端一致；飞书功能色轮转，无紫/霓虹） */
function renderTimeDistribution(list) {
  if (!list.length) {
    timeDistChart.setOption({ graphic: emptyGraphic('暂无时段分布数据') }, true)
    return
  }
  timeDistChart.setOption(
    {
      tooltip: {
        trigger: 'item',
        formatter: (p) => `${p.name}<br/>${p.value} 条（${p.percent}%）`
      },
      legend: { bottom: 0, type: 'scroll', fontSize: 12, textStyle: { color: CHART.axisText } },
      color: CHART.palette,
      series: [
        {
          type: 'pie',
          radius: ['38%', '66%'],
          center: ['50%', '46%'],
          itemStyle: { borderRadius: 4, borderColor: CHART.pieGap, borderWidth: 2 },
          label: { formatter: '{b}\n{d}%', fontSize: 12, color: CHART.labelText },
          data: list.map((r) => ({ name: r.slot, value: r.count }))
        }
      ]
    },
    true
  )
}

/** ECharts 实例初始化（统一深色无特殊主题，resize 跟随容器） */
function initCharts() {
  usageChart = echarts.init(usageRateEl.value)
  trendChart = echarts.init(trendEl.value)
  timeDistChart = echarts.init(timeDistEl.value)
  actualPeopleChart = echarts.init(actualPeopleEl.value)
  window.addEventListener('resize', handleResize)
}

function handleResize() {
  usageChart && usageChart.resize()
  trendChart && trendChart.resize()
  timeDistChart && timeDistChart.resize()
  actualPeopleChart && actualPeopleChart.resize()
}

onMounted(() => {
  initCharts()
  loadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  usageChart && usageChart.dispose()
  trendChart && trendChart.dispose()
  timeDistChart && timeDistChart.dispose()
  actualPeopleChart && actualPeopleChart.dispose()
  usageChart = trendChart = timeDistChart = actualPeopleChart = null
})
</script>

<style scoped>
/* 页面限宽由全局 .page-container-wide 提供；筛选条由全局 .toolbar 提供 */

/* 卡片间距与圆角统一；同一行图表卡片靠 height:100% 与 el-col 拉伸等高 */
.filter-card,
.chart-card {
  margin-bottom: var(--space-lg);
  border-radius: var(--radius-lg);
}

.chart-card {
  height: 100%;
}

.chart-title {
  font-size: 14px;
  line-height: 22px;
  font-weight: 600;
  color: var(--text-primary);
}

.chart-sub {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-placeholder);
  margin: var(--space-xs) 0 var(--space-md);
}

.chart-box {
  height: 380px;
}

.chart-box-tall {
  height: 320px;
}

.chart-box-mid {
  height: 320px;
}

.row-muted {
  margin-left: var(--space-sm);
  font-size: 12px;
  color: var(--text-placeholder);
}

.count-sep {
  margin: 0 var(--space-xs);
  color: var(--text-placeholder);
}

.count-normal {
  color: var(--el-color-success-dark-2);
}

.count-partial {
  color: var(--el-color-warning-dark-2);
}

.count-noshow {
  color: var(--el-color-danger-dark-2);
}
</style>
