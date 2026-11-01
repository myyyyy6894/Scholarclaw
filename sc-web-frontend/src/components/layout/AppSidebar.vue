<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Component } from 'vue'

import {
  Bell,
  BookOpen,
  CalendarClock,
  CircleHelp,
  Home,
  Library,
  LogIn,
  Settings,
  Sparkles,
} from '@lucide/vue'
import { useRoute } from 'vue-router'

import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { notifySuccess, notifyWarning } from '@/utils/notify'
import { copyOperatorQq, readRuntimeOperatorQq, resolveOperatorQq } from '@/utils/operator-contact'
import { unreadBadge } from '@/views/notifications/notification-center'

/**
 * 工作台左侧导航（P1，参考图 `首页.png` / `首页图片.png`）。
 *
 * 白底、灰字、浅蓝选中态 + 左侧 3px 主色条；导航只暴露真实存在的页面。
 * 「消息通知」不是独立路由，点击打开通知抽屉；「帮助与反馈」显示部署方联系 QQ
 * （`SC_OPERATOR_QQ`，未配置时只提示暂无在线联系方式）。
 *
 * 同一份组件在桌面侧栏与移动端抽屉中复用（`expanded` 控制是否强制展开文字）。
 */
interface NavEntry {
  key: string
  label: string
  icon: Component
  routeName: string
}

const props = withDefaults(
  defineProps<{
    /** 抽屉中使用时强制展开，不受 <1024px 图标栏规则影响。 */
    expanded?: boolean
    unreadCount?: number
    ddlPendingCount?: number
  }>(),
  { expanded: false, unreadCount: 0, ddlPendingCount: 0 },
)

const emit = defineEmits<{ navigate: [] }>()

const route = useRoute()
const auth = useAuthStore()
const ui = useUiStore()

const helpOpen = ref(false)

const primaryNav = computed<NavEntry[]>(() => {
  if (!auth.isAuthenticated) {
    return [
      { key: 'home', label: '首页', icon: Home, routeName: 'home' },
      { key: 'login', label: '登录 / 注册', icon: LogIn, routeName: 'login' },
    ]
  }
  return [
    { key: 'home', label: '首页', icon: Home, routeName: 'home' },
    { key: 'rag', label: 'AI 学习', icon: Sparkles, routeName: 'rag' },
    { key: 'knowledge', label: '知识库', icon: Library, routeName: 'knowledge' },
    { key: 'courses', label: '我的课程', icon: BookOpen, routeName: 'courses' },
    { key: 'ddl', label: 'DDL 任务', icon: CalendarClock, routeName: 'ddl' },
  ]
})

const ddlBadge = computed(() => unreadBadge(props.ddlPendingCount))
const notificationBadge = computed(() => unreadBadge(props.unreadCount))
const operatorQq = computed(() => resolveOperatorQq(readRuntimeOperatorQq()))

async function copyQq(): Promise<void> {
  if (await copyOperatorQq(operatorQq.value)) {
    notifySuccess('QQ 号已复制。')
    return
  }
  notifyWarning('复制失败，请手动选择号码复制。')
}

function isActive(entry: NavEntry): boolean {
  return route.name === entry.routeName
}

function openNotifications(): void {
  ui.openNotificationDrawer()
  emit('navigate')
}

function openHelp(): void {
  helpOpen.value = true
  emit('navigate')
}
</script>

<template>
  <aside class="app-sidebar" :class="{ 'app-sidebar--expanded': expanded }">
    <router-link
      class="app-sidebar__brand"
      :to="{ name: 'home' }"
      aria-label="ScholarClaw 智学爪首页"
      @click="emit('navigate')"
    >
      <span class="app-sidebar__logo">
        <img src="/illustrations/logo.svg" alt="" />
      </span>
      <span class="app-sidebar__brand-text">
        <strong>ScholarClaw</strong>
        <small>智 学 爪</small>
      </span>
    </router-link>

    <nav class="app-sidebar__nav" aria-label="主导航">
      <router-link
        v-for="entry in primaryNav"
        :key="entry.key"
        class="app-nav-item"
        :class="{ 'app-nav-item--active': isActive(entry) }"
        :to="{ name: entry.routeName }"
        :title="entry.label"
        :aria-current="isActive(entry) ? 'page' : undefined"
        @click="emit('navigate')"
      >
        <span class="app-nav-item__bar" aria-hidden="true" />
        <component :is="entry.icon" class="app-nav-item__icon" :size="19" :stroke-width="1.8" />
        <span class="app-nav-item__label">{{ entry.label }}</span>
        <span
          v-if="entry.key === 'ddl' && ddlBadge !== ''"
          class="app-nav-item__badge"
          :title="`${ddlPendingCount} 个待处理任务`"
        >
          {{ ddlBadge }}
        </span>
      </router-link>

      <button
        v-if="auth.isAuthenticated"
        type="button"
        class="app-nav-item"
        :class="{ 'app-nav-item--active': ui.notificationDrawerOpen }"
        title="消息通知"
        @click="openNotifications"
      >
        <span class="app-nav-item__bar" aria-hidden="true" />
        <Bell class="app-nav-item__icon" :size="19" :stroke-width="1.8" />
        <span class="app-nav-item__label">消息通知</span>
        <span
          v-if="notificationBadge !== ''"
          class="app-nav-item__badge app-nav-item__badge--danger"
          :title="`${unreadCount} 条未读通知`"
        >
          {{ notificationBadge }}
        </span>
      </button>
    </nav>

    <div v-if="auth.isAuthenticated" class="app-sidebar__nav app-sidebar__nav--secondary">
      <p class="app-sidebar__section">更多</p>
      <router-link
        class="app-nav-item"
        :class="{ 'app-nav-item--active': route.name === 'settings' }"
        :to="{ name: 'settings' }"
        title="设置"
        @click="emit('navigate')"
      >
        <span class="app-nav-item__bar" aria-hidden="true" />
        <Settings class="app-nav-item__icon" :size="19" :stroke-width="1.8" />
        <span class="app-nav-item__label">设置</span>
      </router-link>
      <button type="button" class="app-nav-item" title="帮助与反馈" @click="openHelp">
        <span class="app-nav-item__bar" aria-hidden="true" />
        <CircleHelp class="app-nav-item__icon" :size="19" :stroke-width="1.8" />
        <span class="app-nav-item__label">帮助与反馈</span>
      </button>
    </div>

    <div class="app-sidebar__spacer" />

    <div v-if="auth.isAuthenticated" class="app-sidebar__quote" aria-hidden="true">
      <img src="/illustrations/sidebar-sailboat.png" alt="" />
      <p>让学习更智能<br />让知识有回声</p>
    </div>

    <router-link
      v-if="auth.isAuthenticated"
      class="app-sidebar__user"
      :to="{ name: 'settings' }"
      title="个人设置"
      @click="emit('navigate')"
    >
      <UserAvatar :name="auth.displayName" size="sm" online />
      <span class="app-sidebar__user-text">
        <strong>{{ auth.displayName }}</strong>
        <small>学生</small>
      </span>
      <Settings class="app-sidebar__user-gear" :size="16" :stroke-width="1.8" />
    </router-link>
    <router-link
      v-else
      class="app-sidebar__login"
      :to="{ name: 'login' }"
      title="登录 / 注册"
      @click="emit('navigate')"
    >
      <LogIn :size="18" :stroke-width="1.8" />
      <span class="app-sidebar__user-text"><strong>登录 / 注册</strong></span>
    </router-link>

    <el-dialog v-model="helpOpen" title="帮助与反馈" width="440px" append-to-body>
      <div class="app-sidebar__help">
        <template v-if="operatorQq">
          <p>使用中遇到问题，可以加维护者 QQ 反馈（加好友请备注 ScholarClaw）：</p>
          <div class="app-sidebar__qq">
            <span class="app-sidebar__qq-number">{{ operatorQq }}</span>
            <el-button size="small" @click="copyQq">复制</el-button>
          </div>
          <p class="app-sidebar__help-note">
            QQ 仅用于问题反馈与答疑；DDL 提醒以站内通知为准，加好友不会额外推送。
          </p>
        </template>
        <p v-else>帮助文档与在线反馈入口尚未接入；如需支持，请联系部署者。</p>
      </div>
      <template #footer>
        <el-button type="primary" @click="helpOpen = false">知道了</el-button>
      </template>
    </el-dialog>
  </aside>
</template>

<style scoped>
.app-sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: var(--sc-sidebar-width);
  height: 100%;
  padding: 20px 14px 18px;
  overflow-y: auto;
  border-right: 1px solid var(--sc-border);
  background: var(--sc-surface);
}

.app-sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 4px 18px;
}

/* 只显示 logo.svg 里的图形标记；文字用主题变量重绘，深色模式下才不会变成深蓝底深色字。 */
.app-sidebar__logo {
  display: block;
  flex: none;
  width: 34px;
  height: 34px;
  overflow: hidden;
}

.app-sidebar__logo img {
  display: block;
  width: auto;
  height: 34px;
}

.app-sidebar__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.15;
}

.app-sidebar__brand-text strong {
  color: var(--sc-text);
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.2px;
}

.app-sidebar__brand-text small {
  color: var(--sc-text-placeholder);
  font-size: 10px;
  letter-spacing: 2px;
}

.app-sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.app-sidebar__nav--secondary {
  margin-top: 14px;
}

.app-sidebar__section {
  padding: 0 12px 6px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  letter-spacing: 0.08em;
}

.app-nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 42px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  color: var(--sc-text-secondary);
  background: transparent;
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-body);
  text-align: left;
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.app-nav-item:hover {
  color: var(--sc-text);
  background: var(--sc-surface-hover);
}

.app-nav-item--active {
  color: var(--sc-primary-strong);
  background: var(--sc-active);
  font-weight: 600;
}

.app-nav-item__bar {
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: transparent;
}

.app-nav-item--active .app-nav-item__bar {
  background: var(--sc-primary);
}

.app-nav-item__icon {
  flex: none;
}

.app-nav-item__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-nav-item__badge {
  flex: none;
  min-width: 20px;
  padding: 0 6px;
  border-radius: var(--sc-radius-pill);
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  font-size: var(--sc-font-hint);
  font-weight: 600;
  line-height: 20px;
  text-align: center;
}

.app-nav-item__badge--danger {
  color: var(--sc-danger);
  background: var(--sc-danger-soft);
}

.app-sidebar__spacer {
  flex: 1;
  min-height: 12px;
}

.app-sidebar__quote {
  position: relative;
  margin: 8px 2px 14px;
  overflow: hidden;
  border-radius: var(--sc-radius-card);
  /* 插画自带浅色背景：深色模式下也保留浅色卡片，避免直接铺在深色底上。 */
  background: #eaf2ff;
}

.app-sidebar__quote img {
  display: block;
  width: 100%;
  height: 128px;
  object-fit: cover;
  opacity: 0.9;
}

.app-sidebar__quote p {
  position: absolute;
  left: 14px;
  bottom: 14px;
  color: #1f2937;
  font-size: var(--sc-font-caption);
  font-weight: 600;
  line-height: 1.6;
}

.app-sidebar__user,
.app-sidebar__login {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 8px;
  border-radius: 10px;
  color: var(--sc-text);
  text-decoration: none;
  transition: background-color 0.18s ease;
}

.app-sidebar__user:hover,
.app-sidebar__login:hover {
  background: var(--sc-surface-hover);
}

.app-sidebar__user-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.app-sidebar__user-text strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-sidebar__user-text small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.app-sidebar__user-gear {
  flex: none;
  color: var(--sc-text-placeholder);
}

.app-sidebar__help {
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.app-sidebar__qq {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-button);
  background: var(--sc-primary-soft);
}

.app-sidebar__qq-number {
  color: var(--sc-text);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.app-sidebar__help-note {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

/* ---------- <1024px：图标栏 ---------- */
@media (max-width: 1023px) {
  .app-sidebar:not(.app-sidebar--expanded) {
    width: var(--sc-sidebar-rail-width);
    align-items: center;
    padding: 18px 10px;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__logo {
    width: 34px;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__brand {
    justify-content: center;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-nav-item {
    justify-content: center;
    gap: 0;
    padding: 0;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-nav-item__label,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__brand-text,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__section,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__quote,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__user-text,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__user-gear {
    display: none;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-nav-item__badge {
    position: absolute;
    top: 4px;
    right: 4px;
    min-width: 16px;
    padding: 0 4px;
    line-height: 16px;
  }

  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__user,
  .app-sidebar:not(.app-sidebar--expanded) .app-sidebar__login {
    justify-content: center;
    padding: 6px;
  }
}

@media (max-width: 639px) {
  .app-sidebar {
    width: 100%;
    border-right: 0;
  }
}
</style>
