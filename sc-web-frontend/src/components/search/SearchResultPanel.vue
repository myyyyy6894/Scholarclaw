<script setup lang="ts">
import ComingSoonBadge from '@/components/ui/ComingSoonBadge.vue'

/**
 * 全局搜索结果面板（P1 壳层，P5 收口）。
 *
 * 后端没有搜索接口，这里只如实展示「暂未开发」；**不允许**用路由跳转冒充搜索结果。
 */
defineProps<{ query: string }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <section class="search-panel" aria-live="polite">
    <header class="search-panel__header">
      <span class="search-panel__title">
        {{ query.trim() === '' ? '全局搜索' : `搜索“${query.trim()}”` }}
      </span>
      <button type="button" class="search-panel__close" aria-label="关闭搜索结果" @click="$emit('close')">
        Esc
      </button>
    </header>

    <div class="search-panel__body">
      <ComingSoonBadge hint="后端接口待接入" />
      <p class="search-panel__text">
        搜索将覆盖课程、课件、DDL 任务与会话记录；接口就绪前不会返回伪造结果。
      </p>
    </div>

    <footer class="search-panel__footer">
      按 Enter 搜索 · 按 Esc 关闭
    </footer>
  </section>
</template>

<style scoped>
.search-panel {
  position: absolute;
  top: calc(100% + 10px);
  left: 0;
  z-index: 30;
  width: 100%;
  min-width: 320px;
  padding: 16px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow-hover);
}

.search-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sc-border);
}

.search-panel__title {
  overflow: hidden;
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-panel__close {
  padding: 2px 8px;
  border: 1px solid var(--sc-border);
  border-radius: 6px;
  color: var(--sc-text-placeholder);
  background: var(--sc-surface-muted);
  font-family: var(--sc-font-mono);
  font-size: var(--sc-font-hint);
  cursor: pointer;
}

.search-panel__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 0 12px;
}

.search-panel__text {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.65;
}

.search-panel__footer {
  padding-top: 10px;
  border-top: 1px solid var(--sc-border);
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}
</style>
