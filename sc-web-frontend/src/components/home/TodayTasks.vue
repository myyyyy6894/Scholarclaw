<script setup lang="ts">
import { CalendarClock, ChevronRight } from '@lucide/vue'

import type { DdlTask, DdlTaskStatus } from '@/api/ddl'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { compactDeadline } from '@/views/home/dashboard-data'
import { countdown, statusLabel, statusTagType } from '@/views/ddl/ddl-task'
import type { ModuleState } from '@/views/home/use-home-data'

/**
 * 首页今日待办（P2）。
 *
 * 数据来自 `listDdlTasks({ status_group: 'ALL' })` 的 `active_tasks`，按截止时间升序。
 * 首页只读，不在这里做完成/编辑，避免与 DDL 工作台产生两套写路径。
 */
defineProps<{
  state: ModuleState<DdlTask[]>
  nowMs: number
  authenticated: boolean
}>()

defineEmits<{ retry: [] }>()

type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

function toneFor(status: DdlTaskStatus): Tone {
  const type = statusTagType(status)
  return type === 'info' ? 'neutral' : type
}
</script>

<template>
  <BaseCard title="今日待办" subtitle="按截止时间排序">
    <template #actions>
      <router-link class="card-link" :to="{ name: 'ddl' }">
        查看全部 <ChevronRight :size="14" :stroke-width="1.9" />
      </router-link>
    </template>

    <div v-if="state.status === 'loading'" class="task-skeleton" aria-hidden="true">
      <div v-for="index in 4" :key="index" class="task-skeleton__row">
        <span class="task-skeleton__dot" />
        <span class="task-skeleton__text" />
        <span class="task-skeleton__tag" />
      </div>
    </div>

    <div v-else-if="state.status === 'error'" class="module-error">
      <p>{{ state.error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>

    <EmptyState
      v-else-if="state.status === 'empty'"
      image="/illustrations/achievement-trophy.png"
      :title="authenticated ? '今天没有待办任务' : '登录后查看待办'"
      :description="
        authenticated
          ? '暂无进行中的 DDL；新增任务或等待通知提取后再来看看。'
          : '登录后即可查看你的 DDL 任务与截止时间。'
      "
      compact
    >
      <template #action>
        <router-link class="card-link" :to="{ name: authenticated ? 'ddl' : 'login' }">
          {{ authenticated ? '前往 DDL 工作台' : '登录 / 注册' }}
        </router-link>
      </template>
    </EmptyState>

    <ul v-else class="task-list">
      <li v-for="task in state.data" :key="task.task_id">
        <router-link class="task-row" :to="{ name: 'ddl' }">
          <span class="task-row__mark" :class="`task-row__mark--${toneFor(task.status)}`">
            <CalendarClock :size="15" :stroke-width="1.8" />
          </span>
          <span class="task-row__main">
            <strong>{{ task.task_title }}</strong>
            <small>{{ task.course_name }} · {{ compactDeadline(task) }}</small>
          </span>
          <span class="task-row__side">
            <TagBadge :label="statusLabel(task.status)" :tone="toneFor(task.status)" dot />
            <small v-if="countdown(task, nowMs) !== null" :class="{ 'is-overdue': countdown(task, nowMs)?.overdue }">
              {{ countdown(task, nowMs)?.text }}
            </small>
          </span>
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

.task-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
  transition: background-color 0.18s ease;
}

.task-row:hover {
  background: var(--sc-surface-hover);
}

.task-row__mark {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
}

.task-row__mark--primary {
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.task-row__mark--success {
  color: var(--sc-success);
  background: var(--sc-success-soft);
}

.task-row__mark--warning {
  color: var(--sc-warning);
  background: var(--sc-warning-soft);
}

.task-row__mark--danger {
  color: var(--sc-danger);
  background: var(--sc-danger-soft);
}

.task-row__mark--neutral {
  color: var(--sc-text-secondary);
  background: var(--sc-surface-muted);
}

.task-row__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.task-row__main strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-row__main small {
  overflow: hidden;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-row__side {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
}

.task-row__side small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
}

.task-row__side small.is-overdue {
  color: var(--sc-danger);
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

.task-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-skeleton__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
}

.task-skeleton__dot {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--sc-surface-muted);
}

.task-skeleton__text {
  flex: 1;
  height: 12px;
  border-radius: 6px;
  background: var(--sc-surface-muted);
}

.task-skeleton__tag {
  width: 56px;
  height: 18px;
  border-radius: var(--sc-radius-pill);
  background: var(--sc-surface-muted);
}
</style>
