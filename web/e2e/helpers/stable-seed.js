/**
 * 稳定造数辅助（E2E 专用，供学生端 5 个页面用例共享）
 *
 * 背景（重要）：
 *  后端会话为「单账号单会话」——Redis 键 auth:token:{userId} 只保留最近一次登录的 Token，
 *  同一账号再次登录会让旧 Token 立即 401（实测确认）。
 *  因此本项目的 E2E 造数必须遵守：
 *    · 需要学生身份调用接口时，一律用「页面 localStorage 里的 Token」（pageToken），
 *      不要在 loginAs 之后再 apiLogin 同一账号，否则页面 Token 会被顶掉；
 *    · 不要在 loginAs 之前 apiLogin 同一账号再用其 Token 做收尾（会 401 静默失败）；
 *    · 管理员 Token 仅在真正要用（造已通过预约）时即时获取，缩短被并行任务顶掉的窗口。
 *
 * 另：并行任务在共享同一套种子数据，故造数一律「先冲突检测挑空档，再提交」，
 * 避免别的任务已占用目标时段导致 400。
 */
import { checkConflict, createReservation, auditReservation } from './api'
import { expectOk } from './api'
import { SLOT_FALLBACKS, openDate, openSlots } from './data'

/** 读取当前页面已登录用户的 Token（最可靠的“最新有效 Token”） */
export function pageToken(page) {
  return page.evaluate(() => localStorage.getItem('reservation_token'))
}

/**
 * 冲突检测（解析响应体），true = 该时段可预约。
 *
 * 注意：false 的成因有两种——「时段冲突」与「该日/该时段不开放」（后者也是业务码 400）。
 * 只挑时段本身用不到区分；需要区分时用 canReserve。
 */
export async function isSlotFree(request, token, { classroomId, date, startTime, endTime }) {
  const res = await checkConflict(request, token, { classroomId, date, startTime, endTime })
  return !!(res.body && res.body.code === 200 && res.body.data && res.body.data.conflict === false)
}

/**
 * 判断该时段「可以下预约单」——只有业务码 200 才是可预约。
 * 返回 {@link {ok:boolean, reason?:string}}；reason 原样带上后端文案（未开放 / 时段冲突），
 * 便于造数失败时一眼看出是数据不巧还是业务拦住了。
 */
export async function canReserve(request, token, { classroomId, date, startTime, endTime }) {
  const res = await checkConflict(request, token, { classroomId, date, startTime, endTime })
  if (res.body && res.body.code === 200 && res.body.data && res.body.data.conflict === false) {
    return { ok: true }
  }
  return { ok: false, reason: (res.body && res.body.message) || `业务码 ${res.body && res.body.code}` }
}

/**
 * 在候选 (date × slot) 中挑第一个可预约的时段，造一条「已通过」预约（提交 → 管理员审核通过）。
 *
 * 时间漂移兜底：传入的 dates 若全落在周末/闭馆日（或时段跨了 12:00-14:00 闭馆），
 * 不会直接抛错，而是自动顺延到后面若干个工作日、并用开放窗口内的默认时段重试一遍。
 * 这样用例不会因为「今天恰好是周日」而整片红。
 *
 * @returns {{id:number,date:string,startTime:string,endTime:string}}
 */
export async function seedApprovedAny(request, { adminToken, studentToken, classroomId, dates, slots, purpose }) {
  const candidates = buildCandidates(dates, slots)
  const misses = []
  for (const { date, startTime, endTime } of candidates) {
    const verdict = await canReserve(request, studentToken, { classroomId, date, startTime, endTime })
    if (!verdict.ok) {
      misses.push(`${date} ${startTime}-${endTime}：${verdict.reason}`)
      continue
    }
    const created = await createReservation(request, studentToken, {
      classroomId,
      reserveDate: date,
      startTime,
      endTime,
      purpose
    })
    const id = expectOk(created, `造已通过预约-提交（教室 ${classroomId} ${date} ${startTime}-${endTime}）`)
    const audited = await auditReservation(request, adminToken, id, 1)
    expectOk(audited, `造已通过预约-审核通过（id=${id}）`)
    return { id, date, startTime, endTime }
  }
  throw new Error(
    `无可用时段造数：教室 ${classroomId} / 候选 ${candidates.length} 个全部不可预约\n` +
      misses.slice(0, 6).join('\n')
  )
}

/**
 * 生成「首选候选 + 兜底候选」序列，按日期优先编排。
 * 兜底部分：从明天起顺延 12 个工作日 × 开放窗口内的默认时段，
 * 顺序在首选之后，因此正常运行时首选候选一定先被尝试，行为与改动前一致。
 * @param {string[]} dates 首选日期
 * @param {Array<Array<string>>} slots 首选时段
 */
export function buildCandidates(dates, slots) {
  const seen = new Set()
  const result = []
  const push = (date, slot) => {
    const key = `${date} ${slot[0]}-${slot[1]}`
    if (seen.has(key)) return
    seen.add(key)
    result.push({ date, startTime: slot[0], endTime: slot[1] })
  }
  for (const date of dates) for (const slot of slots) push(date, slot)
  const fallbackDates = []
  for (let i = 1; fallbackDates.length < 12; i += 1) {
    const date = openDate(i)
    if (fallbackDates[fallbackDates.length - 1] !== date) fallbackDates.push(date)
  }
  for (const date of fallbackDates) for (const slot of openSlots(SLOT_FALLBACKS)) push(date, slot)
  return result
}