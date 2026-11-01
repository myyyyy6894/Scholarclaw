<script setup lang="ts">
import { computed } from 'vue'

import type { NotificationBizType, NotificationItem } from '@/api/notifications'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import {
  bizTypeLabel,
  bizTypeTagType,
  formatNotificationTime,
  outboundStatusLabel,
} from '@/views/notifications/notification-center'
import type { ModuleState } from '@/views/home/use-home-data'

/**
 * 首页通知提醒（P2）。
 *
 * 未读优先、最多 4 条；点击由 HomeView 负责标记已读并跳转对应页面。
 */
const props = defineProps<{
  state: ModuleState<NotificationItem[]>
  unreadCount: number
  authenticated: boolean
}>()

defineEmits<{ retry: []; open: [item: NotificationItem]; viewAll: [] }>()

const subtitleText = computed(() => {
  if (!props.authenticated) {
    return '登录后查看'
  }
  if (props.state.status === 'loading') {
    return '正在读取…'
  }
  if (props.state.status === 'error') {
    return '读取失败'
  }
  return props.unreadCount > 0 ? `${props.unreadCount} 条未读` : '暂无未读'
})

type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

const TONE_BY_TYPE: Record<NotificationBizType, Tone> = {
  DDL_T24H: 'warning',
  DDL_T6H: 'danger',
  DDL_OVERDUE: 'danger',
  DDL_FOLLOW_UP: 'primary',
  DOC_INGEST_READY: 'success',
  DOC_INGEST_FAILED: 'danger',
  COURSE_REBUILD_DONE: 'success',
}

function toneFor(type: NotificationBizType): Tone {
  const tone = bizTypeTagType(type)
  return tone === 'info' ? (TONE_BY_TYPE[type] ?? 'neutral') : tone
}
</script>

<template>
  <BaseCard title="通知提醒" :subtitle="subtitleText">
    <template #actions>
      <button type="button" class="card-link" @click="$emit('viewAll')">
        查看全部
      </button>
    </template>

    <div v-if="state.status === 'loading'" class="notice-skeleton" aria-hidden="true">
      <div v-for="index in 3" :key="index" class="notice-skeleton__row">
        <span class="notice-skeleton__dot" />
        <span class="notice-skeleton__text" />
      </div>
    </div>

    <div v-else-if="state.status === 'error'" class="module-error">
      <p>{{ state.error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>

    <EmptyState
      v-else-if="state.status === 'empty'"
      image="/illustrations/status-schedule.png"
      :title="authenticated ? '暂无通知' : '登录后查看通知'"
      :description="
        authenticated
          ? 'DDL 提醒、课件解析结果与重建完成通知会出现在这里。'
          : '登录后即可接收 DDL 提醒与课件处理结果。'
      "
      compact
    />

    <ul v-else class="notice-list">
      <li v-for="item in state.data" :key="item.notification_id">
        <button type="button" class="notice-item" @click="$emit('open', item)">
          <span class="notice-item__dot" :class="`notice-item__dot--${toneFor(item.biz_type)}`" />
          <span class="notice-item__main">
            <span class="notice-item__top">
              <span class="notice-item__title">{{ item.title }}</span>
              <span class="notice-item__time">{{ formatNotificationTime(item.created_at) }}</span>
            </span>
            <span class="notice-item__content">{{ item.content }}</span>
            <span class="notice-item__meta">
              <TagBadge :label="bizTypeLabel(item.biz_type)" :tone="toneFor(item.biz_type)" />
              <span>{{ outboundStatusLabel(item.outbound_status) }}</span>
              <span v-if="item.read_status === 'UNREAD'" class="notice-item__unread">未读</span>
            </span>
          </span>
        </button>
      </li>
    </ul>
  </BaseCard>
</template>

<style scoped>
.card-link {
  padding: 0;
  border: 0;
  color: var(--sc-primary);
  background: transparent;
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-caption);
  cursor: pointer;
}

.card-link:hover {
  color: var(--sc-primary-strong);
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.notice-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 9px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease;
}

.notice-item:hover,
.notice-item:focus-visible {
  background: var(--sc-surface-hover);
  outline: none;
}

.notice-item__dot {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 50%;
}

.notice-item__dot--primary {
  background: var(--sc-primary);
}

.notice-item__dot--success {
  background: var(--sc-success);
}

.notice-item__dot--warning {
  background: var(--sc-warning);
}

.notice-item__dot--danger {
  background: var(--sc-danger);
}

.notice-item__dot--neutral {
  background: var(--sc-text-placeholder);
}

.notice-item__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.notice-item__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.notice-item__title {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-item__time {
  flex: none;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
}

.notice-item__content {
  display: -webkit-box;
  overflow: hidden;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.notice-item__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.notice-item__unread {
  margin-left: auto;
  color: var(--sc-danger);
  font-weight: 600;
}

.module-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 4px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.notice-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.notice-skeleton__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.notice-skeleton__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sc-surface-muted);
}

.notice-skeleton__text {
  flex: 1;
  height: 12px;
  border-radius: 6px;
  background: var(--sc-surface-muted);
}
</style>
