<script setup lang="ts">
/**
 * 统一空状态（P2）。
 *
 * 插画放在浅色框内：素材自带浅色背景，深色模式下不直接铺在深色底上。
 */
withDefaults(
  defineProps<{
    image?: string
    imageAlt?: string
    title: string
    description?: string
    compact?: boolean
    /** 无图片时是否保留插画框。 */
    framed?: boolean
  }>(),
  { image: '', imageAlt: '', description: '', compact: false, framed: true },
)
</script>

<template>
  <div class="empty-state" :class="{ 'empty-state--compact': compact }">
    <div v-if="image !== ''" class="empty-state__frame">
      <img :src="image" :alt="imageAlt" loading="lazy" />
    </div>
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="description !== ''" class="empty-state__description">{{ description }}</p>
    <div v-if="$slots.action" class="empty-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 22px 12px;
  text-align: center;
}

.empty-state__frame {
  display: grid;
  place-items: center;
  width: 100%;
  max-width: 220px;
  padding: 10px;
  border-radius: var(--sc-radius-card);
  background: #f1f6ff;
}

.empty-state__frame img {
  display: block;
  width: 100%;
  max-height: 120px;
  object-fit: contain;
}

.empty-state__title {
  margin-top: 2px;
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
}

.empty-state__description {
  max-width: 320px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-caption);
  line-height: 1.6;
}

.empty-state__action {
  margin-top: 4px;
}

.empty-state--compact {
  padding: 14px 8px;
}

.empty-state--compact .empty-state__frame {
  max-width: 150px;
  padding: 6px;
}

.empty-state--compact .empty-state__frame img {
  max-height: 82px;
}

.empty-state--compact .empty-state__title {
  font-size: var(--sc-font-caption);
}
</style>
