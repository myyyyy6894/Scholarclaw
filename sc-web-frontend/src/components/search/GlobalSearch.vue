<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { Search } from '@lucide/vue'

import SearchResultPanel from '@/components/search/SearchResultPanel.vue'

/**
 * 顶栏全局搜索框（Prompt §3 / §5）。
 *
 * Ctrl/⌘ + K 聚焦，Enter 打开结果面板，Esc 关闭。没有搜索接口，所以面板只显示
 * 「暂未开发」；这里刻意不接任何路由跳转，避免把跳转伪装成搜索。
 */
const rootEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)
const query = ref('')
const panelOpen = ref(false)

function focusInput(): void {
  const input = inputEl.value
  if (input === null) {
    return
  }
  input.focus()
  input.select()
}

function handleKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    focusInput()
    return
  }
  if (event.key === 'Escape' && panelOpen.value) {
    event.preventDefault()
    panelOpen.value = false
  }
}

function handleSubmit(): void {
  panelOpen.value = true
}

function handleFocus(): void {
  if (query.value.trim() !== '') {
    panelOpen.value = true
  }
}

function handleDocumentPointerDown(event: PointerEvent): void {
  const root = rootEl.value
  if (root !== null && event.target instanceof Node && !root.contains(event.target)) {
    panelOpen.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  document.addEventListener('pointerdown', handleDocumentPointerDown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
})
</script>

<template>
  <div ref="rootEl" class="global-search">
    <form class="global-search__field" role="search" @submit.prevent="handleSubmit">
      <Search class="global-search__icon" :size="17" :stroke-width="1.8" aria-hidden="true" />
      <input
        ref="inputEl"
        v-model="query"
        type="search"
        class="global-search__input"
        placeholder="搜索课程、文档、问题…"
        aria-label="全局搜索"
        autocomplete="off"
        @focus="handleFocus"
      />
      <kbd class="global-search__kbd">Ctrl K</kbd>
    </form>

    <SearchResultPanel v-if="panelOpen" :query="query" @close="panelOpen = false" />
  </div>
</template>

<style scoped>
.global-search {
  position: relative;
  width: min(420px, 100%);
}

.global-search__field {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 42px;
  padding: 0 14px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-pill);
  background: var(--sc-surface);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.global-search__field:focus-within {
  border-color: var(--sc-primary);
  box-shadow: 0 0 0 3px var(--sc-primary-soft);
}

.global-search__icon {
  flex: none;
  color: var(--sc-text-placeholder);
}

.global-search__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sc-text);
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-body);
}

.global-search__input::placeholder {
  color: var(--sc-text-placeholder);
}

.global-search__input::-webkit-search-cancel-button {
  display: none;
}

.global-search__kbd {
  flex: none;
  padding: 2px 7px;
  border: 1px solid var(--sc-border);
  border-radius: 6px;
  color: var(--sc-text-placeholder);
  background: var(--sc-surface-muted);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
}

@media (max-width: 760px) {
  .global-search {
    width: 100%;
  }

  .global-search__kbd {
    display: none;
  }
}
</style>
