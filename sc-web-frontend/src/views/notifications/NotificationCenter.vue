<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { X } from '@lucide/vue'
import { useRouter } from 'vue-router'

import {
  listNotifications,
  markNotificationsRead,
  type NotificationFilter,
  type NotificationItem,
} from '@/api/notifications'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { notifyError } from '@/utils/notify'
import {
  bizTypeLabel,
  bizTypeTagType,
  canMarkAll,
  formatNotificationTime,
  mergeNotificationPage,
  notificationTarget,
  outboundStatusLabel,
  readStatusParam,
} from '@/views/notifications/notification-center'

/**
 * 站内通知中心（TASK-802，P1 改为右侧抽屉）。
 *
 * 只调用 `API-NOTIF-001/002`：列表始终按后端 `created_at DESC` 分页，未读数独立返回；
 * 标记已读后重新拉取第一页，避免前端本地推算 `read_at` 或未读数。
 *
 * 抽屉由 `ui` store 控制，顶栏铃铛与侧栏「消息通知」共用同一个入口；
 * 未读数同步写入 store，侧栏角标不再重复请求接口。
 */
const PAGE_SIZE = 10
const POLL_INTERVAL_MS = 60_000

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

const filter = ref<NotificationFilter>('UNREAD')
const items = ref<NotificationItem[]>([])
const unreadCount = ref(0)
const page = ref(1)
const totalPages = ref(0)
const loading = ref(false)
const markingAll = ref(false)

const drawerOpen = computed({
  get: () => ui.notificationDrawerOpen,
  set: (value: boolean) => {
    if (value) {
      ui.openNotificationDrawer()
    } else {
      ui.closeNotificationDrawer()
    }
  },
})

let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling(): void {
  stopPolling()
  pollTimer = setInterval(() => {
    if (auth.isAuthenticated && !loading.value) {
      void refresh(true)
    }
  }, POLL_INTERVAL_MS)
}

async function refresh(reset: boolean): Promise<void> {
  if (!auth.isAuthenticated || loading.value) {
    return
  }
  loading.value = true
  try {
    const targetPage = reset ? 1 : page.value + 1
    const data = await listNotifications({
      readStatus: readStatusParam(filter.value),
      page: targetPage,
      size: PAGE_SIZE,
    })
    items.value = mergeNotificationPage(items.value, data.items, reset)
    unreadCount.value = data.unread_count
    ui.setUnreadNotificationCount(data.unread_count)
    page.value = data.page
    totalPages.value = data.total_pages
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function handleFilterChange(): void {
  page.value = 1
  totalPages.value = 0
  void refresh(true)
}

async function markAll(): Promise<void> {
  if (!canMarkAll(unreadCount.value) || markingAll.value) {
    return
  }
  markingAll.value = true
  try {
    await markNotificationsRead({ mark_all: true })
    await refresh(true)
  } catch (error) {
    notifyError(error)
  } finally {
    markingAll.value = false
  }
}

async function openNotification(item: NotificationItem): Promise<void> {
  try {
    if (item.read_status === 'UNREAD') {
      await markNotificationsRead({
        mark_all: false,
        notification_ids: [item.notification_id],
      })
      await refresh(true)
    }
  } catch (error) {
    notifyError(error)
  } finally {
    drawerOpen.value = false
    await router.push({ name: notificationTarget(item.biz_type) })
  }
}

watch(drawerOpen, (open) => {
  if (open) {
    void refresh(true)
  }
})

watch(
  () => auth.isAuthenticated,
  (authenticated) => {
    if (authenticated) {
      void refresh(true)
      startPolling()
    } else {
      stopPolling()
      items.value = []
      unreadCount.value = 0
      totalPages.value = 0
      ui.setUnreadNotificationCount(0)
      drawerOpen.value = false
    }
  },
  { immediate: true },
)

onBeforeUnmount(stopPolling)
</script>

<template>
  <el-drawer
    v-if="auth.isAuthenticated"
    v-model="drawerOpen"
    class="notification-drawer"
    :with-header="false"
    size="380px"
  >
    <div class="notification-panel">
      <header class="notification-panel__header">
        <div class="notification-panel__title">
          <strong>站内通知</strong>
          <span v-if="unreadCount > 0" class="notification-panel__count">
            {{ unreadCount }} 条未读
          </span>
        </div>
        <div class="notification-panel__actions">
          <el-button
            text
            size="small"
            :disabled="!canMarkAll(unreadCount)"
            :loading="markingAll"
            @click="markAll"
          >
            全部已读
          </el-button>
          <button
            type="button"
            class="notification-panel__close"
            aria-label="关闭通知中心"
            @click="drawerOpen = false"
          >
            <X :size="18" :stroke-width="1.8" />
          </button>
        </div>
      </header>

      <el-radio-group
        v-model="filter"
        size="small"
        class="notification-panel__filter"
        @change="handleFilterChange"
      >
        <el-radio-button value="UNREAD">未读</el-radio-button>
        <el-radio-button value="ALL">全部</el-radio-button>
      </el-radio-group>

      <div v-loading="loading" class="notification-panel__body">
        <el-empty
          v-if="items.length === 0 && !loading"
          description="暂无通知"
          :image-size="56"
        />
        <ul v-else class="notification-list">
          <li v-for="item in items" :key="item.notification_id">
            <button
              type="button"
              class="notification-item"
              :class="{ 'notification-item--unread': item.read_status === 'UNREAD' }"
              @click="openNotification(item)"
            >
              <span class="notification-item__top">
                <el-tag size="small" :type="bizTypeTagType(item.biz_type)">
                  {{ bizTypeLabel(item.biz_type) }}
                </el-tag>
                <span class="notification-item__time">
                  {{ formatNotificationTime(item.created_at) }}
                </span>
              </span>
              <strong class="notification-item__title">{{ item.title }}</strong>
              <span class="notification-item__content">{{ item.content }}</span>
              <span class="notification-item__meta">
                <span>{{ outboundStatusLabel(item.outbound_status) }}</span>
                <span v-if="item.read_status === 'UNREAD'" class="notification-item__dot">
                  未读
                </span>
              </span>
            </button>
          </li>
        </ul>

        <el-button
          v-if="page < totalPages"
          text
          size="small"
          class="notification-panel__more"
          :loading="loading"
          @click="refresh(false)"
        >
          加载更多
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
:global(.notification-drawer .el-drawer__body) {
  padding: 0;
}

.notification-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  padding: 20px;
}

.notification-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.notification-panel__title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.notification-panel__title strong {
  color: var(--sc-text);
  font-size: var(--sc-font-heading);
  font-weight: var(--sc-font-heading-weight);
}

.notification-panel__count {
  color: var(--sc-danger);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.notification-panel__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
}

.notification-panel__close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--sc-text-placeholder);
  background: transparent;
  cursor: pointer;
}

.notification-panel__close:hover {
  color: var(--sc-text);
  background: var(--sc-surface-hover);
}

.notification-panel__filter {
  align-self: flex-start;
}

.notification-panel__body {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
}

.notification-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.notification-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface-muted);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.notification-item:hover,
.notification-item:focus-visible {
  border-color: var(--sc-primary);
  background: var(--sc-primary-soft);
  outline: none;
}

.notification-item--unread {
  border-color: var(--sc-border);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.notification-item__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.notification-item__time {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
}

.notification-item__title {
  color: var(--sc-text);
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
}

.notification-item__content {
  display: -webkit-box;
  overflow: hidden;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.55;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.notification-item__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.notification-item__dot {
  color: var(--sc-danger);
  font-weight: 600;
}

.notification-panel__more {
  display: block;
  margin: 10px auto 0;
}

@media (max-width: 480px) {
  :global(.notification-drawer) {
    max-width: 92vw;
  }

  .notification-panel {
    padding: 16px;
  }
}
</style>
