import { describe, expect, it } from 'vitest'

import { firstVisitGuideTarget } from '@/views/guide/first-visit'

describe('首次登录后的使用指南跳转', () => {
  it('没看过且没有 redirect：去 /guide', () => {
    expect(firstVisitGuideTarget(false, false, '/login')).toBe('/guide')
  })

  it('已经看过：不打扰，返回 null', () => {
    expect(firstVisitGuideTarget(true, false, '/login')).toBeNull()
  })

  it('带着明确 redirect（被守卫踢回登录）时优先原目标', () => {
    expect(firstVisitGuideTarget(false, true, '/login')).toBeNull()
  })

  it('已经在指南页时不重复跳转', () => {
    expect(firstVisitGuideTarget(false, false, '/guide')).toBeNull()
  })
})
