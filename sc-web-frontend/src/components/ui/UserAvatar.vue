<script setup lang="ts">
import { computed } from 'vue'

/**
 * 用户头像（P1）。
 *
 * 后端没有头像上传接口，因此不做假照片：用姓名首字 + 稳定色相生成渐变头像。
 * 色相只由姓名决定，同一用户在不同页面保持一致。
 */
const props = withDefaults(
  defineProps<{
    name: string
    size?: 'sm' | 'md' | 'lg'
    /** 右下角在线状态点；不传则不显示。 */
    online?: boolean
  }>(),
  { size: 'md', online: false },
)

const initial = computed(() => {
  const trimmed = props.name.trim()
  return trimmed === '' ? '?' : trimmed.slice(0, 1).toUpperCase()
})

const hue = computed(() => {
  let hash = 0
  for (const char of props.name) {
    hash = (hash * 31 + (char.codePointAt(0) ?? 0)) % 360
  }
  return hash
})

const style = computed(() => ({
  background: `linear-gradient(135deg, hsl(${hue.value} 82% 62%), hsl(${(hue.value + 38) % 360} 74% 54%))`,
}))
</script>

<template>
  <span
    class="user-avatar"
    :class="[`user-avatar--${size}`, { 'user-avatar--online': online }]"
    :style="style"
    :title="name"
    aria-hidden="true"
  >
    {{ initial }}
    <span v-if="online" class="user-avatar__status" />
  </span>
</template>

<style scoped>
.user-avatar {
  position: relative;
  display: inline-grid;
  flex: none;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-weight: 600;
  letter-spacing: 0.2px;
  user-select: none;
}

.user-avatar--sm {
  width: 30px;
  height: 30px;
  font-size: 12px;
}

.user-avatar--md {
  width: 38px;
  height: 38px;
  font-size: 14px;
}

.user-avatar--lg {
  width: 52px;
  height: 52px;
  font-size: 18px;
}

.user-avatar__status {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 9px;
  height: 9px;
  border: 2px solid var(--sc-surface);
  border-radius: 50%;
  background: var(--sc-success);
}
</style>
