<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'

import { Monitor, Moon, Sun } from '@lucide/vue'

import { useTheme } from '@/composables/use-theme'
import type { ThemeMode } from '@/composables/use-theme'

/**
 * 主题切换（P1）。
 *
 * 三态：浅色 / 深色 / 跟随系统。首次访问跟随系统，用户选择后写入
 * `localStorage['sc.theme']`（非敏感偏好，不涉及任何密钥）。
 */
interface ThemeOption {
  value: ThemeMode
  label: string
  icon: Component
}

const OPTIONS: ThemeOption[] = [
  { value: 'light', label: '浅色模式', icon: Sun },
  { value: 'dark', label: '深色模式', icon: Moon },
  { value: 'system', label: '跟随系统', icon: Monitor },
]

const { mode, setThemeMode } = useTheme()
const current = computed(
  () => OPTIONS.find((option) => option.value === mode.value) ?? OPTIONS[2],
)

function isThemeMode(value: unknown): value is ThemeMode {
  return value === 'light' || value === 'dark' || value === 'system'
}

function handleCommand(command: string | number | object): void {
  if (isThemeMode(command)) {
    setThemeMode(command)
  }
}
</script>

<template>
  <el-dropdown trigger="click" placement="bottom-end" @command="handleCommand">
    <button
      type="button"
      class="theme-toggle"
      :title="`主题：${current.label}`"
      aria-label="切换主题"
    >
      <component :is="current.icon" :size="18" :stroke-width="1.8" />
    </button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="option in OPTIONS"
          :key="option.value"
          :command="option.value"
          :class="{ 'theme-toggle__item--active': option.value === mode }"
        >
          <component :is="option.icon" :size="16" :stroke-width="1.8" />
          <span>{{ option.label }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.theme-toggle {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--sc-border);
  border-radius: 50%;
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;
}

.theme-toggle:hover,
.theme-toggle:focus-visible {
  border-color: var(--sc-primary);
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
  outline: none;
}

:deep(.theme-toggle__item--active) {
  color: var(--sc-primary);
  font-weight: 600;
}

:deep(.el-dropdown-menu__item) {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
