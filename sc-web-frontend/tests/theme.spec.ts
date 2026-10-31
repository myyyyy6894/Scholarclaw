import { describe, expect, it } from 'vitest'

import {
  initTheme,
  normalizeThemeMode,
  resolveTheme,
  setThemeMode,
  THEME_STORAGE_KEY,
  useTheme,
} from '@/composables/use-theme'
import { browserStorage } from '@/utils/browser-storage'

/**
 * 主题解析与持久化（P1）。
 *
 * 运行在 node 环境，因此只验证纯逻辑与存储行为；DOM 属性写入由浏览器构建验证。
 */
describe('theme mode', () => {
  it('显式模式优先于系统偏好', () => {
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('system 模式跟随 prefers-color-scheme', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
  })

  it('非法历史值回落到 system', () => {
    expect(normalizeThemeMode('dark')).toBe('dark')
    expect(normalizeThemeMode('light')).toBe('light')
    expect(normalizeThemeMode('system')).toBe('system')
    expect(normalizeThemeMode('sepia')).toBe('system')
    expect(normalizeThemeMode(null)).toBe('system')
    expect(normalizeThemeMode(42)).toBe('system')
  })

  it('持久化键只表示主题偏好，不包含任何密钥语义', () => {
    expect(THEME_STORAGE_KEY).toBe('sc.theme')
    expect(THEME_STORAGE_KEY).not.toMatch(/api[_-]?key|password|secret|token/i)
  })

  it('切换主题会更新状态并写入浏览器存储', () => {
    setThemeMode('dark')
    expect(useTheme().mode.value).toBe('dark')
    expect(browserStorage.read(THEME_STORAGE_KEY)).toBe('dark')

    setThemeMode('system')
    expect(useTheme().mode.value).toBe('system')
    expect(browserStorage.read(THEME_STORAGE_KEY)).toBe('system')
  })

  it('无 window 环境调用 initTheme 不抛异常', () => {
    expect(() => initTheme()).not.toThrow()
  })
})
