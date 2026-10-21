<script setup lang="ts">
import { computed } from 'vue'

import { useRouter } from 'vue-router'

import { logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifyWarning } from '@/utils/notify'

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
  void doLogout()
}

async function doLogout(): Promise<void> {
  try {
    if (auth.isAuthenticated) {
      await logout()
    }
  } catch (error) {
    // 登出接口失败（令牌已过期、网络异常）不阻止本地清理：本地会话必须立即失效
    notifyError(error)
  } finally {
    auth.clearSession()
    notifyWarning('已退出当前会话。')
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <el-container class="sc-layout">
    <el-header class="sc-layout__header">
      <router-link class="sc-layout__brand" :to="{ name: 'home' }">{{ appTitle }}</router-link>
      <div class="sc-layout__actions">
        <span class="sc-layout__user">{{ userLabel }}</span>
        <router-link v-if="auth.isAuthenticated" class="sc-layout__link" :to="{ name: 'courses' }">
          课程空间
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-layout__link" :to="{ name: 'settings' }">
          模型配置
        </router-link>
        <router-link v-else class="sc-layout__link" :to="{ name: 'login' }">登录</router-link>
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
  color: #303133;
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
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

.sc-layout__link {
  color: #409eff;
  font-size: 14px;
  text-decoration: none;
}

.sc-layout__main {
  padding: 16px;
}
</style>
