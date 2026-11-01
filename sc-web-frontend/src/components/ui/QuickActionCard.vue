<script setup lang="ts">
import { ArrowRight } from '@lucide/vue'
import type { RouteLocationRaw } from 'vue-router'

/**
 * 首页快捷入口卡（P2）。
 *
 * 插画使用 `public/illustrations/feature-*.png`；图标只在「进入」箭头处使用，
 * 不用图标或 emoji 替代插画。
 */
withDefaults(
  defineProps<{
    title: string
    description: string
    image: string
    to: RouteLocationRaw
    tone?: 'blue' | 'green' | 'purple' | 'orange' | 'pink'
  }>(),
  { tone: 'blue' },
)
</script>

<template>
  <router-link class="quick-action" :class="`quick-action--${tone}`" :to="to">
    <span class="quick-action__tile">
      <img :src="image" alt="" loading="lazy" />
    </span>
    <span class="quick-action__text">
      <strong>{{ title }}</strong>
      <small>{{ description }}</small>
    </span>
    <ArrowRight class="quick-action__arrow" :size="16" :stroke-width="1.8" aria-hidden="true" />
  </router-link>
</template>

<style scoped>
.quick-action {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.quick-action:hover,
.quick-action:focus-visible {
  border-color: var(--sc-primary);
  box-shadow: var(--sc-shadow);
  outline: none;
  transform: translateY(-1px);
}

.quick-action__tile {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  overflow: hidden;
  border-radius: 14px;
}

.quick-action--blue .quick-action__tile {
  background: #eaf2ff;
}

.quick-action--green .quick-action__tile {
  background: #e7faf1;
}

.quick-action--purple .quick-action__tile {
  background: #f2eeff;
}

.quick-action--orange .quick-action__tile {
  background: #fff3e4;
}

.quick-action--pink .quick-action__tile {
  background: #ffeef5;
}

.quick-action__tile img {
  display: block;
  width: 44px;
  height: 44px;
  object-fit: contain;
}

.quick-action__text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.quick-action__text strong {
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
}

.quick-action__text small {
  overflow: hidden;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-action__arrow {
  align-self: flex-end;
  color: var(--sc-text-placeholder);
  transition: color 0.18s ease, transform 0.18s ease;
}

.quick-action:hover .quick-action__arrow {
  color: var(--sc-primary);
  transform: translateX(2px);
}
</style>
