<script setup lang="ts">
import { computed } from 'vue'

import { useRoute, useRouter } from 'vue-router'

import { logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifyWarning } from '@/utils/notify'
import NotificationCenter from '@/views/notifications/NotificationCenter.vue'

/**
 * 全局工作台外壳（`06` TASK-103 / TASK-404）。
 *
 * 左侧品牌导航 + 右侧内容区，保留既有路由与权限判断；导航只展示已存在的页面，
 * 不增加未实现的入口。窄屏时侧栏折叠为顶部横向导航，保证 1536 桌面与移动宽度都可用。
 */

const appTitle = import.meta.env.VITE_APP_TITLE || 'ScholarClaw 智学爪'
const PAGE_META: Record<string, { title: string; subtitle: string }> = {
  home: { title: '学习工作台', subtitle: '连接课程知识库、DDL 与 AI 助手' },
  courses: { title: '课程空间', subtitle: '管理课程与 Embedding 模型绑定' },
  knowledge: { title: '课程知识库', subtitle: '上传课件并跟踪真实解析状态' },
  rag: { title: '课程问答', subtitle: '基于课件的溯源回答与引用' },
  ddl: { title: 'DDL 待办', subtitle: '通知提取、待确认收件箱与任务闭环' },
  settings: { title: '模型配置', subtitle: 'BYOK 密钥与模型连接设置' },
}

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const userLabel = computed(() => (auth.isAuthenticated ? auth.displayName : '未登录'))
const userInitial = computed(() => userLabel.value.slice(0, 1).toUpperCase())
const pageMeta = computed(
  () => PAGE_META[String(route.name ?? '')] ?? { title: 'ScholarClaw', subtitle: '' },
)

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
  <div class="sc-shell">
    <aside class="sc-sidebar">
      <router-link class="sc-brand" :to="{ name: 'home' }">
        <span class="sc-brand__mark">SC</span>
        <span class="sc-brand__text">
          <strong>{{ appTitle }}</strong>
          <small>智学爪 · 学习工作台</small>
        </span>
      </router-link>

      <nav class="sc-nav">
        <router-link class="sc-nav__item" title="首页" aria-label="首页" :class="{ 'sc-nav__item--active': route.name === 'home' }" :to="{ name: 'home' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z" />
          </svg>
          <span>首页</span>
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-nav__item" title="课程空间" aria-label="课程空间" :class="{ 'sc-nav__item--active': route.name === 'courses' }" :to="{ name: 'courses' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 4h7v7H4zm9 0h7v7h-7zM4 13h7v7H4zm9 0h7v7h-7z" />
          </svg>
          <span>课程空间</span>
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-nav__item" title="课程知识库" aria-label="课程知识库" :class="{ 'sc-nav__item--active': route.name === 'knowledge' }" :to="{ name: 'knowledge' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 3h9a4 4 0 0 1 4 4v14H8a3 3 0 0 1-3-3zm3 3v2h7V6zm0 5v2h7v-2z" />
          </svg>
          <span>课程知识库</span>
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-nav__item" title="课程问答" aria-label="课程问答" :class="{ 'sc-nav__item--active': route.name === 'rag' }" :to="{ name: 'rag' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 4h16v12H7l-3 3z" />
          </svg>
          <span>课程问答</span>
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-nav__item" title="DDL 待办" aria-label="DDL 待办" :class="{ 'sc-nav__item--active': route.name === 'ddl' }" :to="{ name: 'ddl' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M7 2h10a2 2 0 0 1 2 2v18l-7-4-7 4V4a2 2 0 0 1 2-2zm1 5v2h8V7zm0 4v2h5v-2z" />
          </svg>
          <span>DDL 待办</span>
        </router-link>
        <router-link v-if="auth.isAuthenticated" class="sc-nav__item" title="模型配置" aria-label="模型配置" :class="{ 'sc-nav__item--active': route.name === 'settings' }" :to="{ name: 'settings' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" />
          </svg>
          <span>模型配置</span>
        </router-link>
        <router-link v-else class="sc-nav__item" title="登录" aria-label="登录" :class="{ 'sc-nav__item--active': route.name === 'login' }" :to="{ name: 'login' }">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 3a5 5 0 1 1 0 10 5 5 0 0 1 0-10zm0 12c4.4 0 8 2.2 8 5v1H4v-1c0-2.8 3.6-5 8-5z" />
          </svg>
          <span>登录</span>
        </router-link>
      </nav>

      <div v-if="auth.isAuthenticated" class="sc-sidebar__footer">
        <div class="sc-user">
          <span class="sc-user__avatar">{{ userInitial }}</span>
          <span class="sc-user__text">
            <strong>{{ userLabel }}</strong>
            <small>学生</small>
          </span>
        </div>
        <button type="button" class="sc-logout" @click="handleLogout">退出</button>
      </div>
      <div v-else class="sc-sidebar__footer">
        <router-link class="sc-logout sc-logout--link" :to="{ name: 'login' }">登录 / 注册</router-link>
      </div>
    </aside>

    <div class="sc-main">
      <header class="sc-topbar">
        <div>
          <h1>{{ pageMeta.title }}</h1>
          <p v-if="pageMeta.subtitle">{{ pageMeta.subtitle }}</p>
        </div>
        <div class="sc-topbar__actions">
          <NotificationCenter />
          <span class="sc-topbar__badge">MVP · P8</span>
        </div>
      </header>
      <main class="sc-content">
        <div class="sc-page">
          <router-view />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.sc-shell {
  display: flex;
  min-height: 100vh;
  background: var(--sc-bg);
}

.sc-sidebar {
  display: flex;
  flex-direction: column;
  flex: 0 0 248px;
  padding: 22px 16px;
  color: #e6efff;
  background: linear-gradient(180deg, #0d1b36 0%, #14294d 55%, #1b3a6b 100%);
}

.sc-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  color: inherit;
  text-decoration: none;
  padding: 0 6px 22px;
}

.sc-brand__mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #fff;
  background: linear-gradient(135deg, #4f8cff, #7b5cff);
  box-shadow: 0 8px 18px rgba(79, 140, 255, 0.35);
}

.sc-brand__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.25;
}

.sc-brand__text strong {
  font-size: 15px;
  font-weight: 650;
}

.sc-brand__text small {
  color: #9fb4d8;
  font-size: 11px;
}

.sc-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sc-nav__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-radius: 10px;
  color: #c9d8f2;
  font-size: 14px;
  text-decoration: none;
  transition: background 0.18s ease, color 0.18s ease;
}

.sc-nav__item svg {
  width: 18px;
  height: 18px;
  fill: currentColor;
  opacity: 0.9;
}

.sc-nav__item:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.sc-nav__item--active {
  color: #ffffff;
  background: linear-gradient(90deg, rgba(79, 140, 255, 0.32), rgba(79, 140, 255, 0.1));
  box-shadow: inset 3px 0 0 #6ea2ff;
}

.sc-sidebar__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.sc-user {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  overflow: hidden;
}

.sc-user__avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  font-weight: 600;
}

.sc-user__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.sc-user__text strong {
  max-width: 96px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sc-user__text small {
  color: #9fb4d8;
  font-size: 11px;
}

.sc-logout {
  flex: 0 0 auto;
  white-space: nowrap;
  padding: 6px 12px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 8px;
  color: #dbe7ff;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  text-decoration: none;
}

.sc-logout:hover {
  background: rgba(255, 255, 255, 0.1);
}

.sc-logout--link {
  width: 100%;
  text-align: center;
}

.sc-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.sc-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 28px 10px;
}

.sc-topbar h1 {
  margin: 0;
  color: var(--sc-text);
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.2px;
}

.sc-topbar p {
  margin: 6px 0 0;
  color: var(--sc-muted);
  font-size: 13px;
}

.sc-topbar__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.sc-topbar__badge {
  flex: none;
  padding: 6px 12px;
  border-radius: 999px;
  color: #3d6fd8;
  background: #e8f0ff;
  font-size: 12px;
  font-weight: 600;
}

.sc-content {
  flex: 1;
  padding: 8px 28px 32px;
  overflow-x: hidden;
}

@media (max-width: 600px) {
  .sc-brand__text {
    display: none;
  }

  .sc-nav {
    justify-content: center;
  }

  .sc-nav__item {
    padding: 8px 10px;
  }

  .sc-nav__item span {
    display: none;
  }

  .sc-topbar__badge {
    display: none;
  }
}

@media (max-width: 900px) {
  .sc-shell {
    flex-direction: column;
  }

  .sc-sidebar {
    flex: none;
    flex-direction: row;
    align-items: center;
    gap: 14px;
    padding: 12px 16px;
    overflow-x: auto;
  }

  .sc-brand {
    padding: 0;
    flex: none;
  }

  .sc-brand__text small {
    display: none;
  }

  .sc-nav {
    flex-direction: row;
    flex: 1;
    gap: 4px;
  }

  .sc-nav__item {
    padding: 8px 12px;
    white-space: nowrap;
  }

  .sc-nav__item span {
    font-size: 13px;
  }

  .sc-sidebar__footer {
    margin: 0;
    padding: 0;
    border: 0;
  }

  .sc-user__text {
    display: none;
  }

  .sc-topbar {
    padding: 18px 16px 6px;
  }

  .sc-topbar h1 {
    font-size: 20px;
  }

  .sc-content {
    padding: 8px 16px 24px;
  }
}
</style>
