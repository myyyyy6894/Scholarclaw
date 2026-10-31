import { computed, ref } from 'vue'

import { browserStorage } from '@/utils/browser-storage'

/**
 * 主题状态（P1）。
 *
 * 三种模式：`light` / `dark` / `system`。首次访问默认 `system`，跟随
 * `prefers-color-scheme`；用户显式选择后写入 `localStorage`（**只存主题**，
 * 不含任何密钥——`tests/no-secret-persistence.spec.ts` 的约束不变）。
 *
 * 这个文件刻意不放在 `src/views` 下：现有测试会扫描 `src/views/**` 禁止出现
 * 浏览器存储引用，主题属于允许持久化的非敏感偏好，但仍应和密钥纪律保持隔离。
 */

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'sc.theme'
export const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)'

/** 纯函数：把“模式 + 系统偏好”解析为最终主题，便于 node 环境单测。 */
export function resolveTheme(mode: ThemeMode, systemPrefersDark: boolean): ResolvedTheme {
  if (mode === 'dark') {
    return 'dark'
  }
  if (mode === 'light') {
    return 'light'
  }
  return systemPrefersDark ? 'dark' : 'light'
}

/** 纯函数：容错读取历史值，非法值回落到 `system`。 */
export function normalizeThemeMode(value: unknown): ThemeMode {
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
}

const initialMode = normalizeThemeMode(browserStorage.read<ThemeMode>(THEME_STORAGE_KEY))
const mode = ref<ThemeMode>(initialMode)
const systemPrefersDark = ref(false)
const resolvedTheme = computed<ResolvedTheme>(() =>
  resolveTheme(mode.value, systemPrefersDark.value),
)

let darkQuery: MediaQueryList | null = null
let initialized = false

function applyToDocument(theme: ResolvedTheme): void {
  if (typeof document === 'undefined') {
    return
  }
  const root = document.documentElement
  root.dataset.theme = theme
  // Element Plus 的深色变量挂在 `html.dark` 下，两个标记必须同步。
  root.classList.toggle('dark', theme === 'dark')
}

function handleSystemChange(event: MediaQueryListEvent): void {
  systemPrefersDark.value = event.matches
  if (mode.value === 'system') {
    applyToDocument(resolvedTheme.value)
  }
}

/** 应用启动时调用一次；重复调用无副作用。 */
export function initTheme(): void {
  if (initialized || typeof window === 'undefined') {
    return
  }
  initialized = true
  if (typeof window.matchMedia === 'function') {
    darkQuery = window.matchMedia(DARK_MEDIA_QUERY)
    systemPrefersDark.value = darkQuery.matches
    darkQuery.addEventListener('change', handleSystemChange)
  }
  applyToDocument(resolvedTheme.value)
  // 首帧先不加过渡类，避免启动时从浅色闪到深色。
  window.requestAnimationFrame(() => {
    document.documentElement.classList.add('is-theme-ready')
  })
}

export function setThemeMode(next: ThemeMode): void {
  mode.value = next
  browserStorage.write(THEME_STORAGE_KEY, next)
  applyToDocument(resolvedTheme.value)
}

export function useTheme(): {
  mode: typeof mode
  resolvedTheme: typeof resolvedTheme
  setThemeMode: typeof setThemeMode
} {
  return { mode, resolvedTheme, setThemeMode }
}
