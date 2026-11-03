import request from '@/utils/request'

/**
 * 统计模块接口封装（R5 数据看板，管理员专属）
 * 全部为只读统计：首页数据概览 / 教室使用率排行 / 月度预约趋势 / 热门时段分布
 */

/** 管理端首页数据概览：今日预约 / 待审核 / 教室总数 / 用户总数 */
export function getOverview() {
  return request.get('/stats/overview')
}

/** 教室使用率排行（柱状图）：startDate/endDate 可选，缺省近 30 天 */
export function getUsageRate(params) {
  return request.get('/stats/usage-rate', { params })
}

/** 月度预约趋势（折线图） */
export function getTrend(params) {
  return request.get('/stats/trend', { params })
}

/** 热门时段分布（饼图） */
export function getTimeDistribution(params) {
  return request.get('/stats/time-distribution', { params })
}

/**
 * 实际使用统计（V4.0 模块六）：按实验室聚合登记条数、正常/部分/失约分布、
 * 实际使用人时与实际使用率，用于对比计划占用与实际使用
 */
export function getActualUsage(params) {
  return request.get('/stats/actual-usage', { params })
}
