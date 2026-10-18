<script setup lang="ts">
import { computed } from 'vue'

import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { notifyWarning } from '@/utils/notify'

/**
 * 全局基础布局（`06` TASK-103「建立后续页面所需的基础布局」）。
 *
 * 只提供顶栏 + 主内容区：四个 MVP 视图各自需要左侧课程栏、中间 Tab、右侧速览等更强的布局，
 * 由对应任务在自己的视图内实现，避免在这里提前堆砌用不上的插槽。
 */

const appTitle = import.meta.env.VITE_APP_TITLE || 'ScholarClaw 智学爪'

const auth = useAuthStore()
const router = useRouter()

const userLabel = computed(() => (auth.isAuthenticated ? auth.displayName : '未登录'))

function handleLogout(): void {
  auth.clearSession()
  notifyWarning('已退出当前会话。')
  void router.push({ name: 'home' })
}
</script>

<template>
  <el-container class="sc-layout">
    <el-header class="sc-layout__header">
      <div class="sc-layout__brand">{{ appTitle }}</div>
      <div class="sc-layout__actions">
        <span class="sc-layout__user">{{ userLabel }}</span>
        <el-button v-if="auth.isAuthenticated" text type="primary" @click="handleLogout">
          退出
        </el-button>
      </div>
    </el-header>

    <el-main class="sc-layout__main">
      <div class="sc-page">
        <router-view />
      </div>
    </el-main>
  </el-container>
</template>

<style scoped>
.sc-layout {
  min-height: 100%;
}

.sc-layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.sc-layout__brand {
  font-size: 16px;
  font-weight: 600;
}

.sc-layout__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sc-layout__user {
  color: #606266;
  font-size: 14px;
}

.sc-layout__main {
  padding: 16px;
}
</style>
