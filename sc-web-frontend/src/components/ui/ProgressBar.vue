<script setup lang="ts">
import { computed } from 'vue'

/**
 * 资料就绪度进度条（P2）。
 *
 * 口径固定为 READY / 文档总数；总数为 0 时显示「暂无资料」，**不显示 0%**。
 * 文案由调用方决定，本组件只负责数值展示。
 */
const props = withDefaults(
  defineProps<{
    /** 0~100；`null` 表示没有可用于计算的数据。 */
    value: number | null
    label?: string
    emptyText?: string
    tone?: 'primary' | 'success' | 'warning' | 'danger'
    showValue?: boolean
  }>(),
  { label: '资料就绪度', emptyText: '暂无资料', tone: 'primary', showValue: true },
)

const clamped = computed(() => {
  if (props.value === null || !Number.isFinite(props.value)) {
    return null
  }
  return Math.max(0, Math.min(100, Math.round(props.value)))
})
</script>

<template>
  <div class="progress" :class="`progress--${tone}`">
    <div class="progress__top">
      <span class="progress__label">{{ label }}</span>
      <span v-if="showValue" class="progress__value">
        {{ clamped === null ? emptyText : `${clamped}%` }}
      </span>
    </div>
    <div
      class="progress__track"
      :class="{ 'progress__track--empty': clamped === null }"
      role="progressbar"
      :aria-label="label"
      :aria-valuenow="clamped ?? undefined"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <span
        v-if="clamped !== null"
        class="progress__fill"
        :style="{ width: `${clamped}%` }"
      />
    </div>
  </div>
</template>

<style scoped>
.progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.progress__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.progress__label {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.progress__value {
  color: var(--sc-text);
  font-size: var(--sc-font-hint);
  font-weight: 600;
  white-space: nowrap;
}

.progress__track {
  height: 6px;
  overflow: hidden;
  border-radius: var(--sc-radius-pill);
  background: var(--sc-surface-muted);
}

.progress__track--empty {
  background: var(--sc-border);
}

.progress__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--sc-primary);
  transition: width 0.3s ease;
}

.progress--success .progress__fill {
  background: var(--sc-success);
}

.progress--warning .progress__fill {
  background: var(--sc-warning);
}

.progress--danger .progress__fill {
  background: var(--sc-danger);
}
</style>
