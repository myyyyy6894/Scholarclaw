<script setup lang="ts">
/**
 * 统一「暂未开发」标记（Prompt §5/§10）。
 *
 * 所有后端尚未实现的模块都复用它，保证：
 * 1. 文案只有一种（搜索 `暂未开发` 能列出全部待接入点）；
 * 2. 绝不在占位处渲染假数字或假状态；
 * 3. 深浅色下都保持中性灰，不与真实状态标签混淆。
 */
withDefaults(
  defineProps<{
    /** 主文案，默认「暂未开发」。 */
    label?: string
    /** 一行说明，默认「后端接口待接入」。 */
    hint?: string
    /** 紧凑模式：只显示主文案，不显示说明。 */
    compact?: boolean
  }>(),
  { label: '暂未开发', hint: '后端接口待接入', compact: false },
)
</script>

<template>
  <span class="coming-soon" :class="{ 'coming-soon--compact': compact }">
    <span class="coming-soon__label">
      <span class="coming-soon__dot" aria-hidden="true" />
      {{ label }}
    </span>
    <span v-if="!compact" class="coming-soon__hint">{{ hint }}</span>
  </span>
</template>

<style scoped>
.coming-soon {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
}

.coming-soon__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 9px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-pill);
  color: var(--sc-text-secondary);
  background: var(--sc-surface-muted);
  font-size: var(--sc-font-hint);
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.coming-soon__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--sc-text-placeholder);
}

.coming-soon__hint {
  padding-left: 3px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.4;
}

.coming-soon--compact {
  flex-direction: row;
}
</style>
