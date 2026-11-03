/**
 * 全局前置：
 *   1. 探活后端，避免"后端没起"时每条用例各报一次超时
 *   2. 清理历史运行残留的「E2E 前缀 + 已通过」预约——残留的已通过预约会占满测试教室与时段，
 *      后续造数直接 400「该时段与…的已通过预约冲突」，产生与产品无关的假失败。
 *      清理方式是本人取消（走业务接口，不碰数据库），已取消(3)不参与冲突判定。
 * 两者都不改变任何业务代码，只是把每次运行拉回同一起跑线。
 */
const API_BASE = process.env.E2E_API_BASE || `http://127.0.0.1:${process.env.E2E_API_PORT || '8080'}`

/** 造数账号（与 helpers/auth.js 的 ACCOUNTS 保持一致，此处不 import 以保持 globalSetup 零依赖） */
const SEED_ACCOUNTS = [
  { username: 'zhangsan', password: '123456', role: 0 },
  { username: 'lisi', password: '123456', role: 0 }
]

/** E2E 造数用途前缀（与 helpers/data.js 的 E2E_PURPOSE_PREFIX 一致） */
const E2E_PURPOSE_PREFIX = 'E2E自动化-'

/** 每次运行最多清理的分页数（500 条/页，足以覆盖历史残留） */
const MAX_CLEANUP_PAGES = 4

async function login(account) {
  const resp = await fetch(`${API_BASE}/api/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(account)
  })
  const body = await resp.json()
  if (body.code !== 200 || !body.data || !body.data.token) {
    throw new Error(`清理登录失败（${account.username}）：HTTP ${resp.status} ${JSON.stringify(body).slice(0, 120)}`)
  }
  return body.data.token
}

/** 取消该账号名下、今天及以后、仍为「已通过」的 E2E 预约，返回取消条数 */
async function cancelStaleE2eReservations(token) {
  const today = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10)
  let cancelled = 0
  for (let page = 1; page <= MAX_CLEANUP_PAGES; page++) {
    const resp = await fetch(`${API_BASE}/api/reservation/mine?page=${page}&size=500&status=1`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await resp.json()
    if (body.code !== 200 || !body.data) {
      throw new Error(`查询我的预约失败：HTTP ${resp.status} ${JSON.stringify(body).slice(0, 120)}`)
    }
    const records = body.data.records || []
    if (records.length === 0) break
    for (const r of records) {
      // 只清 E2E 造的、日期未过的：过期记录无法取消（开始前 1 小时内禁止取消），也不影响后续造数
      if (!String(r.purpose || '').startsWith(E2E_PURPOSE_PREFIX) || String(r.reserveDate) < today) continue
      const res = await fetch(`${API_BASE}/api/reservation/${r.id}/cancel`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ reason: 'E2E自动化-历史残留清理' })
      })
      const resBody = await res.json().catch(() => ({}))
      // 单条失败（如临近开始）不影响其余记录清理
      if (resBody.code === 200) cancelled++
    }
  }
  return cancelled
}

export default async function globalSetup() {
  const url = `${API_BASE}/api/user/login`
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin123', role: 1 })
    })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const body = await resp.json()
    if (body.code !== 200) {
      throw new Error(`业务码 ${body.code}：${body.message}`)
    }
  } catch (e) {
    throw new Error(
      `后端探活失败（${url}）：${e.message}\n` +
        '请确认：① MySQL/Redis 容器在线；② 后端已启动。\n' +
        '若本机 8080 被占用，请以 --server.port=8081 启动后端，并设 E2E_API_PORT=8081 后重跑。'
    )
  }

  // 残留清理失败不应阻断整轮回归（探活已过，说明后端可用），只提示
  let total = 0
  for (const account of SEED_ACCOUNTS) {
    try {
      total += await cancelStaleE2eReservations(await login(account))
    } catch (e) {
      console.warn(`[globalSetup] ${account.username} 残留清理跳过：${e.message}`)
    }
  }
  if (total > 0) {
    console.log(`[globalSetup] 已清理历史残留的 E2E 已通过预约 ${total} 条`)
  }
}
