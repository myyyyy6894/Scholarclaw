<script setup lang="ts">
import { ChevronRight, MessagesSquare } from '@lucide/vue'

import type { ChatMessage } from '@/api/rag'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { ChatPair } from '@/views/home/dashboard-data'
import { relativeTimeLabel } from '@/views/home/dashboard-data'
import type { ModuleState } from '@/views/home/use-home-data'
import { splitSupplement } from '@/views/rag/rag-chat'

/**
 * 首页最近问答（P2）。
 *
 * 取当前课程最近一页会话，按 USER + ASSISTANT 配对后取 4 对；只读展示，
 * 点击进入 AI 学习页继续对话。
 */
defineProps<{
  state: ModuleState<ChatMessage[]>
  pairs: ChatPair[]
  courseName: string | null
  nowMs: number
  authenticated: boolean
}>()

defineEmits<{ retry: [] }>()

function excerpt(value: string, max = 90): string {
  const normalized = value.replace(/\s+/g, ' ').trim()
  return normalized.length > max ? `${normalized.slice(0, max)}…` : normalized
}

function answerText(pair: ChatPair): string {
  if (pair.assistant.is_rejected) {
    return pair.assistant.content === '' ? '无资料拒答' : excerpt(pair.assistant.content)
  }
  const main = splitSupplement(pair.assistant.content).main
  return main === '' ? '回答生成中或未完成' : excerpt(main)
}
</script>

<template>
  <BaseCard title="最近问答" :subtitle="courseName ?? '当前课程'">
    <template #actions>
      <router-link class="card-link" :to="{ name: 'rag' }">
        去提问 <ChevronRight :size="14" :stroke-width="1.9" />
      </router-link>
    </template>

    <div v-if="state.status === 'loading'" class="chat-skeleton" aria-hidden="true">
      <div v-for="index in 3" :key="index" class="chat-skeleton__row">
        <span class="chat-skeleton__icon" />
        <span class="chat-skeleton__text" />
      </div>
    </div>

    <div v-else-if="state.status === 'error'" class="module-error">
      <p>{{ state.error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>

    <EmptyState
      v-else-if="state.status === 'empty'"
      image="/illustrations/empty-cat-reading.png"
      :title="authenticated ? '还没有对话' : '登录后查看问答'"
      :description="
        authenticated
          ? '向 AI 提问后，最近的问答与引用会显示在这里。'
          : '登录后即可向 AI 提问并查看最近的问答记录。'
      "
      compact
    >
      <template #action>
        <router-link class="card-link" :to="{ name: authenticated ? 'rag' : 'login' }">
          {{ authenticated ? '去提问' : '登录 / 注册' }}
        </router-link>
      </template>
    </EmptyState>

    <ul v-else class="chat-list">
      <li v-for="pair in pairs" :key="pair.user.message_id">
        <router-link class="chat-row" :to="{ name: 'rag' }">
          <span class="chat-row__icon">
            <MessagesSquare :size="15" :stroke-width="1.7" />
          </span>
          <span class="chat-row__main">
            <strong>{{ excerpt(pair.user.content, 48) }}</strong>
            <small>{{ answerText(pair) }}</small>
          </span>
          <span class="chat-row__time">{{ relativeTimeLabel(pair.assistant.created_at, nowMs) }}</span>
        </router-link>
      </li>
    </ul>
  </BaseCard>
</template>

<style scoped>
.card-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--sc-primary);
  font-size: var(--sc-font-caption);
  text-decoration: none;
}

.card-link:hover {
  color: var(--sc-primary-strong);
}

.chat-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
  transition: background-color 0.18s ease;
}

.chat-row:hover {
  background: var(--sc-surface-hover);
}

.chat-row__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: var(--sc-accent);
  background: var(--sc-accent-soft);
}

.chat-row__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.chat-row__main strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-row__main small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.chat-row__time {
  flex: none;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
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

.chat-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chat-skeleton__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.chat-skeleton__icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--sc-surface-muted);
}

.chat-skeleton__text {
  flex: 1;
  height: 12px;
  border-radius: 6px;
  background: var(--sc-surface-muted);
}
</style>
