<script setup lang="ts">
import { computed } from 'vue'

/**
 * 指标卡（P4）。
 *
 * 只展示调用方传入的真实计数，不在这里推断状态；tone 仅影响数字颜色。
 */
const props = withDefaults(
  defineProps<{
    label: string
    value: number | string
    hint?: string
    tone?: 'default' | 'primary' | 'success' | 'warning' | 'danger'
  }>(),
  { hint: '', tone: 'default' },
)

const displayValue = computed(() =>
  typeof props.value === 'number' ? String(props.value) : props.value,
)
</script>

<template>
  <article class="stat-card" :class="`stat-card--${tone}`">
    <span class="stat-card__label">{{ label }}</span>
    <strong class="stat-card__value">{{ displayValue }}</strong>
    <span v-if="hint !== ''" class="stat-card__hint">{{ hint }}</span>
  </article>
</template>

<style scoped>
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.stat-card__label {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.stat-card__value {
  color: var(--sc-text);
  font-size: 24px;
  font-weight: 650;
  line-height: 1.3;
}

.stat-card__hint {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.stat-card--primary .stat-card__value {
  color: var(--sc-primary-strong);
}

.stat-card--success .stat-card__value {
  color: var(--sc-success);
}

.stat-card--warning .stat-card__value {
  color: #b45309;
}

.stat-card--danger .stat-card__value {
  color: var(--sc-danger);
}

[data-theme="dark"] .stat-card--warning .stat-card__value {
  color: #fbbf24;
}
</style>
