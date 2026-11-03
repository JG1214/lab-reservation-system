/**
 * 冒烟：验证 E2E 骨架可用（登录态注入 / 路由守卫 / 后端连通 / 页面渲染）
 * 骨架不稳时先跑这一条，避免在全量用例里大海捞针。
 */
import { test, expect } from '@playwright/test'
import { loginAs, clearAuth } from '../helpers/auth'

/** 管理端首页数据卡文案，与 AdminWelcome.vue 的 .stat-item 一一对应（顺序同页面）；数量由清单长度决定，不写死 */
const STAT_LABELS = ['今日预约', '待审核', '待登记使用', '今日实到人数', '教室总数', '用户总数']

/** 按卡片文案定位数据卡（避免 nth-child 长链） */
function statItem(page, label) {
  return page.locator('.stat-item').filter({ has: page.locator('.stat-label', { hasText: label }) })
}

test.describe('E2E 骨架冒烟', () => {
  test('未登录访问受保护页面 → 跳登录页并携带回跳地址', async ({ page }) => {
    await clearAuth(page)
    await page.goto('/student/home')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    await expect(page).toHaveTitle(/登录/)
  })

  test('学生登录 → 直达教室列表页并渲染教室数据', async ({ page }) => {
    await loginAs(page, { who: 'student', path: '/student/home' })
    await expect(page).toHaveURL(/\/student\/home/)
    // 种子教室 A101 应在列表中（证明列表接口通了、表格渲染了）
    await expect(page.getByText('A101').first()).toBeVisible()
  })

  test('管理员登录 → 直达管理端首页并渲染 6 张数据卡', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/home' })
    await expect(page).toHaveURL(/\/admin\/home/)
    await expect(page.locator('.stat-item')).toHaveCount(STAT_LABELS.length)
    for (const label of STAT_LABELS) {
      await expect(page.locator('.stat-label', { hasText: label })).toBeVisible()
    }
    // 概览接口回填后「教室总数」一定 > 0（种子库 ≥12 间），用它作为数据回填完成的判据
    await expect
      .poll(async () => Number((await statItem(page, '教室总数').locator('.stat-value').innerText()).trim()))
      .toBeGreaterThan(0)
  })
})