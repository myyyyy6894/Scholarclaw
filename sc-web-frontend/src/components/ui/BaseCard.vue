<script setup lang="ts">
import { useSlots } from 'vue'

/**
 * 工作台基础卡片（P2）。
 *
 * 统一参考图的圆角、边框、柔和阴影与 20px 内边距；标题区与操作区分离，
 * 避免每个页面重复写 card header 样式。
 */
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    /** 页面级卡片默认 20px；表格类卡片可用 none 自己控制内边距。 */
    padding?: 'none' | 'sm' | 'md'
  }>(),
  { title: '', subtitle: '', padding: 'md' },
)

const slots = useSlots()
</script>

<template>
  <section class="base-card" :class="`base-card--pad-${padding}`">
    <header v-if="title !== '' || slots.header || slots.actions" class="base-card__header">
      <div class="base-card__titles">
        <slot name="header">
          <h2 v-if="title !== ''">{{ title }}</h2>
          <p v-if="subtitle !== ''">{{ subtitle }}</p>
        </slot>
      </div>
      <div v-if="slots.actions" class="base-card__actions">
        <slot name="actions" />
      </div>
    </header>

    <div class="base-card__body">
      <slot />
    </div>

    <footer v-if="slots.footer" class="base-card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.base-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.base-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px var(--sc-card-pad) 0;
}

.base-card__titles {
  min-width: 0;
}

.base-card__titles h2 {
  color: var(--sc-text);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.35;
}

.base-card__titles p {
  margin-top: 3px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-caption);
  line-height: 1.5;
}

.base-card__actions {
  flex: none;
}

.base-card__body {
  flex: 1;
  min-height: 0;
}

.base-card__footer {
  padding: 0 var(--sc-card-pad) 16px;
}

.base-card--pad-md .base-card__body {
  padding: 14px var(--sc-card-pad) var(--sc-card-pad);
}

.base-card--pad-sm .base-card__body {
  padding: 10px 14px 14px;
}

.base-card--pad-none .base-card__body {
  padding: 0;
}

.base-card--pad-none .base-card__header {
  padding-bottom: 14px;
}
</style>
