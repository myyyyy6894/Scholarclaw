<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

import { useRouter } from 'vue-router'

import {
  listNotifications,
  markNotificationsRead,
  type NotificationFilter,
  type NotificationItem,
} from '@/api/notifications'
import { useAuthStore } from '@/stores/auth'
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
  unreadBadge,
} from '@/views/notifications/notification-center'

/**
 * 顶部站内通知中心（TASK-802）。
 *
 * 只调用 `API-NOTIF-001/002`：列表始终按后端 `created_at DESC` 分页，未读数独立返回；
 * 标记已读后重新拉取第一页，避免前端本地推算 `read_at` 或未读数。
 * 轮询仅刷新第一页与未读数，不猜测推送状态；IM 实际投递状态由 TASK-804 更新。
 */

const PAGE_SIZE = 10
const POLL_INTERVAL_MS = 60_000

const auth = useAuthStore()
const router = useRouter()

const visible = ref(false)
const filter = ref<NotificationFilter>('UNREAD')
const items = ref<NotificationItem[]>([])
const unreadCount = ref(0)
const page = ref(1)
const totalPages = ref(0)
const loading = ref(false)
const markingAll = ref(false)

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

function handleShow(): void {
  visible.value = true
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
    visible.value = false
    await router.push({ name: notificationTarget(item.biz_type) })
  }
}

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
      visible.value = false
    }
  },
  { immediate: true },
)

onBeforeUnmount(stopPolling)
</script>

<template>
  <el-popover
    v-if="auth.isAuthenticated"
    v-model:visible="visible"
    placement="bottom-end"
    :width="400"
    trigger="click"
    @show="handleShow"
  >
    <template #reference>
      <button
        type="button"
        class="sc-notification-trigger"
        aria-label="站内通知"
        title="站内通知"
      >
        <el-badge
          :value="unreadBadge(unreadCount)"
          :hidden="unreadCount <= 0"
          :max="99"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22zm7-6V11a7 7 0 0 0-5.5-6.84V3a1.5 1.5 0 0 0-3 0v1.16A7 7 0 0 0 5 11v5l-2 2v1h18v-1z"
            />
          </svg>
        </el-badge>
      </button>
    </template>

    <div class="sc-notification-panel">
      <header class="sc-notification-panel__header">
        <div>
          <strong>站内通知</strong>
          <span v-if="unreadCount > 0" class="sc-notification-panel__count">
            {{ unreadCount }} 条未读
          </span>
        </div>
        <el-button
          text
          size="small"
          :disabled="!canMarkAll(unreadCount)"
          :loading="markingAll"
          @click="markAll"
        >
          全部已读
        </el-button>
      </header>

      <el-radio-group
        v-model="filter"
        size="small"
        class="sc-notification-panel__filter"
        @change="handleFilterChange"
      >
        <el-radio-button value="UNREAD">未读</el-radio-button>
        <el-radio-button value="ALL">全部</el-radio-button>
      </el-radio-group>

      <div v-loading="loading" class="sc-notification-panel__body">
        <el-empty
          v-if="items.length === 0 && !loading"
          description="暂无通知"
          :image-size="56"
        />
        <ul v-else class="sc-notification-list">
          <li v-for="item in items" :key="item.notification_id">
            <button
              type="button"
              class="sc-notification-item"
              :class="{ 'sc-notification-item--unread': item.read_status === 'UNREAD' }"
              @click="openNotification(item)"
            >
              <span class="sc-notification-item__top">
                <el-tag size="small" :type="bizTypeTagType(item.biz_type)">
                  {{ bizTypeLabel(item.biz_type) }}
                </el-tag>
                <span class="sc-notification-item__time">
                  {{ formatNotificationTime(item.created_at) }}
                </span>
              </span>
              <strong class="sc-notification-item__title">{{ item.title }}</strong>
              <span class="sc-notification-item__content">{{ item.content }}</span>
              <span class="sc-notification-item__meta">
                <span>{{ outboundStatusLabel(item.outbound_status) }}</span>
                <span v-if="item.read_status === 'UNREAD'" class="sc-notification-item__dot">
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
          class="sc-notification-panel__more"
          :loading="loading"
          @click="refresh(false)"
        >
          加载更多
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.sc-notification-trigger {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--sc-border);
  border-radius: 12px;
  background: var(--sc-surface);
  color: var(--sc-muted);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.sc-notification-trigger:hover,
.sc-notification-trigger:focus-visible {
  color: var(--sc-brand-strong);
  border-color: var(--sc-brand);
  box-shadow: var(--sc-shadow-soft);
  outline: none;
}

.sc-notification-trigger svg {
  width: 19px;
  height: 19px;
  fill: currentColor;
}

.sc-notification-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sc-notification-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sc-notification-panel__header strong {
  font-size: 15px;
}

.sc-notification-panel__count {
  margin-left: 8px;
  color: var(--sc-danger);
  font-size: 12px;
  font-weight: 600;
}

.sc-notification-panel__filter {
  align-self: flex-start;
}

.sc-notification-panel__body {
  min-height: 80px;
  max-height: 420px;
  overflow-y: auto;
}

.sc-notification-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.sc-notification-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: #f8faff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.sc-notification-item:hover,
.sc-notification-item:focus-visible {
  border-color: var(--sc-brand);
  background: var(--sc-brand-soft);
  outline: none;
}

.sc-notification-item--unread {
  border-color: #dce8ff;
  background: #f2f7ff;
}

.sc-notification-item__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sc-notification-item__time {
  color: var(--sc-muted);
  font-size: 11px;
  white-space: nowrap;
}

.sc-notification-item__title {
  color: var(--sc-text);
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
}

.sc-notification-item__content {
  display: -webkit-box;
  overflow: hidden;
  color: var(--sc-muted);
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.sc-notification-item__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--sc-muted);
  font-size: 11px;
}

.sc-notification-item__dot {
  color: var(--sc-danger);
  font-weight: 600;
}

.sc-notification-panel__more {
  display: block;
  margin: 8px auto 0;
}
</style>
