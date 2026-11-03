/**
 * E2E 测试数据约定与日期工具
 *
 * 命名前缀：所有 E2E 造的预约用途一律以 E2E_PURPOSE_PREFIX 开头，
 * 便于跑完统一用 SQL 清理（见本文件尾部注释），不与人工/演示数据混淆。
 */
import dayjs from 'dayjs'

/** 测试预约用途前缀（清理依据，勿改） */
export const E2E_PURPOSE_PREFIX = 'E2E自动化-'

/** 生成带前缀的测试用途 */
export function e2ePurpose(tag) {
  return `${E2E_PURPOSE_PREFIX}${tag}`
}

/**
 * 教室 ID 分配表（database/init_db.sql 固定 12 间，id 1-12）
 * 按用例分组占用，避免两条用例抢同一间教室同一时段造成互相干扰。
 *
 * key 即 room_no（与库内一致），value 只放「稳定且可断言」的字段：
 * - id / type 由 init_db.sql 固定，可安全引用
 * - 教室名称、楼栋等展示字段一律以接口返回为准，不在此维护（库内值会变，硬编码必然过期）
 * 因此断言写 CLASSROOMS.C101.id，不要写 name / building。
 */
export const CLASSROOMS = {
  A101: { id: 1, type: 1 },
  A102: { id: 2, type: 1 },
  A201: { id: 3, type: 2 },
  A301: { id: 4, type: 3 },
  B101: { id: 5, type: 2 },
  B102: { id: 6, type: 2 },
  B201: { id: 7, type: 3 },
  B301: { id: 8, type: 1 },
  C101: { id: 9, type: 1 },
  C201: { id: 10, type: 2 },
  C301: { id: 11, type: 3 },
  C401: { id: 12, type: 1 }
}

/** 学生端页面可见的教室类型下拉选项（前端字典文案） */
export const TYPE_LABELS = { 1: '普通教室', 2: '实验室', 3: '机房' }

/** 预约状态文案（前端字典） */
export const STATUS_LABELS = { 0: '待审核', 1: '已通过', 2: '已驳回', 3: '已取消' }

/**
 * 相对今天的日期（yyyy-MM-dd）。
 * 一律用"未来日期"造预约：后端要求预约开始时间必须晚于当前时间，写死日期的用例会随时间失效。
 */
export function futureDate(daysFromToday) {
  return dayjs().add(daysFromToday, 'day').format('YYYY-MM-DD')
}

/** 今天（yyyy-MM-dd） */
export function today() {
  return dayjs().format('YYYY-MM-DD')
}

/** 当前时间 + n 分钟（HH:mm），用于取消时限这类"贴近当前时刻"的场景 */
export function timeFromNow(minutes) {
  return dayjs().add(minutes, 'minute').format('HH:mm')
}

/**
 * 常用时段（HH:mm 两元素数组）。
 * 四段都落在任意工作日的开放窗口内（08:00-12:00 / 14:00-18:00，见 SLOT_FALLBACKS）。
 * 不要写 12:00-14:00 这类午间段：所有教室每日 12:00-14:00 闭馆，后端会直接 400。
 */
export const SLOTS = {
  morning: ['08:00', '10:00'],
  noon: ['10:00', '12:00'],
  afternoon: ['14:00', '16:00'],
  evening: ['16:00', '18:00']
}

/* ------------------------------------------------------------------ *
 * 开放时段口径（与后端教室开放时段保持一致）
 * ------------------------------------------------------------------ */

/**
 * 教室每日真实开放窗口（HH:mm 两元素数组）。
 *
 * 为什么需要：造数若落在窗口外，后端会直接 400——
 *   「该实验室在所选日期未开放，请选择其他日期」/「所选时间须完整落在开放时段内」。
 * 早期用例用 futureDate(n) 裸造数，一旦命中周末或 12:00-14:00 午间闭馆就整片红，
 * 而这些失败与业务逻辑无关，只是造数时机不巧。
 */
export const OPEN_WINDOWS = [
  ['08:00', '12:00'],
  ['14:00', '18:00']
]

/** 开放窗口内的默认候选时段（供造数 helper 兜底轮换） */
export const SLOT_FALLBACKS = [
  ['08:00', '10:00'],
  ['10:00', '12:00'],
  ['14:00', '16:00'],
  ['16:00', '18:00']
]

/** 是否为工作日（周一~周五）——教室只在工作日开放 */
export function isWeekday(date) {
  const day = dayjs(date).day()
  return day >= 1 && day <= 5
}

/** 以 (date, slot) 判断该时段是否完整落在某个开放窗口内 */
export function inOpenWindow([startTime, endTime]) {
  return OPEN_WINDOWS.some(([openStart, openEnd]) => startTime >= openStart && endTime <= openEnd)
}

/**
 * 第 n 个「往前顺延」的候选日期：从 today+n 起，跳过周六周日。
 * 造数一律用它，保证日期本身存在开放时段，而不是把失败留到接口 400 才发现。
 */
export function openDate(daysFromToday) {
  let cursor = dayjs().add(daysFromToday, 'day')
  while (cursor.day() === 0 || cursor.day() === 6) {
    cursor = cursor.add(1, 'day')
  }
  return cursor.format('YYYY-MM-DD')
}

/** 连续 count 个可用造数日期（跳过周末），用于 DATES / 候选列表 */
export function openDates(daysFromToday, count) {
  const result = []
  let cursor = dayjs().add(daysFromToday, 'day')
  while (result.length < count) {
    if (isWeekday(cursor)) result.push(cursor.format('YYYY-MM-DD'))
    cursor = cursor.add(1, 'day')
  }
  // 去重后可能不足 count 个（起点晚于最后一个工作日时），此时允许跨周补齐
  return [...new Set(result)]
}

/** 把候选时段过滤成「完整落在开放窗口内」的那些 */
export function openSlots(slots = SLOT_FALLBACKS) {
  return slots.filter((slot) => inOpenWindow(slot))
}

/**
 * 今天是否还能造出「今日且尚未开始」的合法预约：当前时刻 + leadMinutes 仍早于当日最后一个开放窗口结束。
 * 周末、或当日窗口已过时返回 false，调用方应据此 test.skip 并说明原因。
 */
export function canSeedToday(leadMinutes) {
  const now = dayjs()
  if (!isWeekday(now)) return false
  const latest = OPEN_WINDOWS[OPEN_WINDOWS.length - 1][1].split(':')
  const endOfDay = now.hour(Number(latest[0])).minute(Number(latest[1])).second(0).millisecond(0)
  return now.add(leadMinutes, 'minute').isBefore(endOfDay)
}

/**
 * 跑完清理测试数据（在本机 Docker 环境执行，非用例的一部分）。
 *
 * 注意：docker exec 管道会把中文 LIKE 模式打成乱码（实测 like '%自动化%' 命中 0 行），
 * 因此这里只用前缀的 ASCII 部分 `E2E%` 匹配，不要写中文：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from reservation where purpose like 'E2E%';"
 *
 * 注册流程用例会遗留测试账号，需一并清理（种子账号 admin/zhangsan/lisi/wangwu/zhaoliu 不受影响）：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from user_favorite where user_id in (select id from sys_user where username like 'e2e%'); delete from reservation where user_id in (select id from sys_user where username like 'e2e%'); delete from sys_user where username like 'e2e%';"
 *
 * 若后端与库在其他机器，请按实际容器名/连接参数替换。
 */