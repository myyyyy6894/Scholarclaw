<script setup lang="ts">
import { computed } from 'vue'

import { Bell, ChevronDown, Menu } from '@lucide/vue'
import { useRouter } from 'vue-router'

import GlobalSearch from '@/components/search/GlobalSearch.vue'
import ThemeToggle from '@/components/layout/ThemeToggle.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { notifyError, notifyWarning } from '@/utils/notify'
import { unreadBadge } from '@/views/notifications/notification-center'

/**
 * 工作台顶栏（P1）。
 *
 * 左：移动端菜单按钮 + 页面标题/副标题；中：全局搜索；右：主题、通知、用户菜单。
 * 通知未读数来自 `ui` store（由通知中心写入），不在顶栏重复请求。
 */
defineProps<{ title: string; subtitle?: string }>()

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

const notificationBadge = computed(() => unreadBadge(ui.unreadNotificationCount))

function handleUserCommand(command: string | number | object): void {
  if (command === 'settings') {
    void router.push({ name: 'settings' })
    return
  }
  if (command === 'logout') {
    void doLogout()
  }
}

async function doLogout(): Promise<void> {
  try {
    if (auth.isAuthenticated) {
      await logout()
    }
  } catch (error) {
    // 登出接口失败（令牌已过期、网络异常）不阻止本地清理：本地会话必须立即失效。
    notifyError(error)
  } finally {
    auth.clearSession()
    notifyWarning('已退出当前会话。')
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <header class="app-topbar">
    <div class="app-topbar__left">
      <button
        type="button"
        class="app-topbar__icon-button app-topbar__menu"
        aria-label="打开导航"
        title="打开导航"
        @click="ui.openMobileSidebar()"
      >
        <Menu :size="20" :stroke-width="1.8" />
      </button>
      <div class="app-topbar__titles">
        <h1>{{ title }}</h1>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
    </div>

    <div class="app-topbar__search">
      <GlobalSearch />
    </div>

    <div class="app-topbar__actions">
      <ThemeToggle />

      <button
        v-if="auth.isAuthenticated"
        type="button"
        class="app-topbar__icon-button"
        aria-label="站内通知"
        title="站内通知"
        @click="ui.openNotificationDrawer()"
      >
        <el-badge :value="notificationBadge" :hidden="notificationBadge === ''" :max="99">
          <Bell :size="19" :stroke-width="1.8" />
        </el-badge>
      </button>

      <el-dropdown
        v-if="auth.isAuthenticated"
        trigger="click"
        placement="bottom-end"
        @command="handleUserCommand"
      >
        <button type="button" class="app-topbar__user" aria-label="用户菜单">
          <UserAvatar :name="auth.displayName" size="sm" />
          <span class="app-topbar__user-name">{{ auth.displayName }}</span>
          <ChevronDown class="app-topbar__user-chevron" :size="15" :stroke-width="1.8" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="settings">个人设置</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <router-link v-else :to="{ name: 'login' }" class="app-topbar__login">
        <el-button type="primary" size="small">登录 / 注册</el-button>
      </router-link>
    </div>
  </header>
</template>

<style scoped>
.app-topbar {
  position: sticky;
  top: 0;
  z-index: var(--sc-z-topbar);
  display: flex;
  flex: none;
  align-items: center;
  gap: 20px;
  min-height: var(--sc-topbar-height);
  padding: 10px max(var(--sc-page-pad), calc((100% - var(--sc-content-max-width)) / 2));
  border-bottom: 1px solid var(--sc-border);
  background: var(--sc-topbar-bg);
  backdrop-filter: blur(8px);
}

.app-topbar__left {
  display: flex;
  flex: 0 1 auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.app-topbar__titles {
  min-width: 0;
}

.app-topbar__titles h1 {
  overflow: hidden;
  color: var(--sc-text);
  font-size: var(--sc-font-heading);
  font-weight: var(--sc-font-heading-weight);
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-topbar__titles p {
  overflow: hidden;
  margin-top: 2px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-topbar__search {
  display: flex;
  flex: 1;
  justify-content: center;
  min-width: 0;
}

.app-topbar__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}

.app-topbar__icon-button {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--sc-border);
  border-radius: 50%;
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;
}

.app-topbar__icon-button:hover,
.app-topbar__icon-button:focus-visible {
  border-color: var(--sc-primary);
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
  outline: none;
}

.app-topbar__menu {
  display: none;
}

.app-topbar__user {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 170px;
  height: 40px;
  padding: 0 10px 0 4px;
  border: 1px solid transparent;
  border-radius: var(--sc-radius-pill);
  background: transparent;
  color: var(--sc-text);
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-caption);
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.app-topbar__user:hover,
.app-topbar__user:focus-visible {
  border-color: var(--sc-border);
  background: var(--sc-surface);
  outline: none;
}

.app-topbar__user-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-topbar__user-chevron {
  flex: none;
  color: var(--sc-text-placeholder);
}

.app-topbar__login {
  text-decoration: none;
}

@media (max-width: 1023px) {
  .app-topbar__search {
    justify-content: flex-end;
  }
}

@media (max-width: 760px) {
  .app-topbar {
    gap: 12px;
  }

  .app-topbar__titles p {
    display: none;
  }

  .app-topbar__search {
    flex: 1;
  }
}

@media (max-width: 639px) {
  .app-topbar {
    gap: 8px;
    padding: 8px 14px;
  }

  .app-topbar__menu {
    display: grid;
  }

  .app-topbar__titles {
    display: none;
  }

  .app-topbar__actions {
    gap: 6px;
  }

  .app-topbar__user {
    padding: 0 2px;
  }

  .app-topbar__user-name,
  .app-topbar__user-chevron {
    display: none;
  }
}
</style>
