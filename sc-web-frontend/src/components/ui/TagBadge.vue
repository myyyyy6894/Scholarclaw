<script setup lang="ts">
/**
 * 状态标签（P2）。
 *
 * 用项目令牌统一 Tag 的胶囊形状与色调；状态文案由调用方传入，组件不发明状态。
 */
withDefaults(
  defineProps<{
    label: string
    tone?: 'primary' | 'success' | 'warning' | 'danger' | 'accent' | 'neutral'
    dot?: boolean
  }>(),
  { tone: 'neutral', dot: false },
)
</script>

<template>
  <span class="tag-badge" :class="`tag-badge--${tone}`">
    <span v-if="dot" class="tag-badge__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped>
.tag-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px;
  border-radius: var(--sc-radius-pill);
  font-size: var(--sc-font-hint);
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.tag-badge__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.tag-badge--primary {
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
}

.tag-badge--success {
  color: var(--sc-success);
  background: var(--sc-success-soft);
}

.tag-badge--warning {
  color: #b45309;
  background: var(--sc-warning-soft);
}

.tag-badge--danger {
  color: var(--sc-danger);
  background: var(--sc-danger-soft);
}

.tag-badge--accent {
  color: var(--sc-accent);
  background: var(--sc-accent-soft);
}

.tag-badge--neutral {
  color: var(--sc-text-secondary);
  background: var(--sc-surface-muted);
}

[data-theme="dark"] .tag-badge--warning {
  color: #fbbf24;
}
</style>
