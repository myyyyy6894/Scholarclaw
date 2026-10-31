<script setup lang="ts">
import AppShell from '@/components/layout/AppShell.vue'

/**
 * 兼容层（P1）。
 *
 * 路由表仍然 import 本文件；真正的布局在 `components/layout/AppShell.vue`。
 * 保留这个薄包装，避免为了换布局而改 `router/index.ts`，也就不会影响
 * 路由 name、鉴权守卫和现有测试。
 */
</script>

<template>
  <AppShell />
</template>
