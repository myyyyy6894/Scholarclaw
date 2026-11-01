<script setup lang="ts">
import { computed } from 'vue'

import { useRoute } from 'vue-router'

import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppTopbar from '@/components/layout/AppTopbar.vue'
import { useShellCounters } from '@/composables/use-shell-counters'
import { useUiStore } from '@/stores/ui'
import NotificationCenter from '@/views/notifications/NotificationCenter.vue'

/**
 * 工作台外壳（P1）。
 *
 * 布局铁律（Prompt §3）：
 * * `.app-shell` 固定 `100dvh`，整页不滚动；
 * * 侧栏固定宽度、内部独立滚动；
 * * 只有 `.app-content` 滚动，顶栏保持可见。
 *
 * `<1024px` 侧栏由 `AppSidebar` 自己折叠成图标栏；`<640px` 隐藏静态侧栏，
 * 由顶栏菜单按钮打开抽屉（仍复用同一个 `AppSidebar`）。
 */
const PAGE_META: Record<string, { title: string; subtitle?: string }> = {
  home: { title: '首页', subtitle: '连接课程知识库、DDL 与 AI 助手' },
  rag: { title: 'AI 学习', subtitle: '基于课程知识库的智能问答与学习规划' },
  knowledge: { title: '知识库', subtitle: '上传课件并跟踪真实解析状态' },
  courses: { title: '我的课程', subtitle: '管理课程空间与 Embedding 绑定' },
  ddl: { title: 'DDL 任务', subtitle: '通知提取、待确认收件箱与任务闭环' },
  settings: { title: '设置', subtitle: '模型配置、偏好与账号安全' },
  guide: { title: '使用指南', subtitle: '功能说明、模型配置与常见问题' },
  login: { title: '登录', subtitle: '登录后开始使用 ScholarClaw' },
}

const route = useRoute()
const ui = useUiStore()
const { ddlPendingCount } = useShellCounters()

const pageMeta = computed(() => {
  const key = String(route.name ?? '')
  const fallbackTitle = typeof route.meta.title === 'string' ? route.meta.title : 'ScholarClaw'
  return PAGE_META[key] ?? { title: fallbackTitle }
})
</script>

<template>
  <div class="app-shell">
    <AppSidebar
      class="app-shell__sidebar"
      :unread-count="ui.unreadNotificationCount"
      :ddl-pending-count="ddlPendingCount"
    />

    <div class="app-main">
      <AppTopbar :title="pageMeta.title" :subtitle="pageMeta.subtitle" />
      <main class="app-content">
        <div class="sc-page">
          <router-view />
        </div>
      </main>
    </div>

    <el-drawer
      v-model="ui.mobileSidebarOpen"
      class="app-mobile-drawer"
      direction="ltr"
      size="264px"
      :with-header="false"
    >
      <AppSidebar
        expanded
        :unread-count="ui.unreadNotificationCount"
        :ddl-pending-count="ddlPendingCount"
        @navigate="ui.closeMobileSidebar()"
      />
    </el-drawer>

    <NotificationCenter />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100dvh;
  overflow: hidden;
  background: var(--sc-bg);
}

.app-shell__sidebar {
  flex: none;
}

.app-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.app-content {
  flex: 1;
  padding: 20px var(--sc-page-pad) 32px;
  overflow-x: hidden;
  overflow-y: auto;
}

:global(.app-mobile-drawer .el-drawer__body) {
  padding: 0;
}

@media (max-width: 639px) {
  .app-shell__sidebar {
    display: none;
  }

  .app-content {
    padding: 14px 14px 24px;
  }
}
</style>
