<script setup lang="ts">
import { ChevronRight, FileText } from '@lucide/vue'

import type { KnowledgeDoc } from '@/api/knowledge'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { useCourseStore } from '@/stores/course'
import { relativeTimeLabel } from '@/views/home/dashboard-data'
import type { ModuleState } from '@/views/home/use-home-data'
import {
  documentStatusLabel,
  documentStatusTagType,
} from '@/views/knowledge/knowledge-form'

/**
 * 首页最近学习（P2）。
 *
 * 数据来自当前课程（`courseStore.selectedCourseId`，否则第一门课程）的 `listDocs`，
 * 排除已删除文档，按 `updated_at` 降序取前 5。
 */
defineProps<{
  state: ModuleState<KnowledgeDoc[]>
  courseName: string | null
  nowMs: number
  authenticated: boolean
}>()

defineEmits<{ retry: [] }>()

const courseStore = useCourseStore()

type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

function toneFor(doc: KnowledgeDoc): Tone {
  const tone = documentStatusTagType(doc.status)
  return tone === 'info' ? 'neutral' : tone
}

function selectCourse(doc: KnowledgeDoc): void {
  courseStore.select(doc.course_id)
}

const fileIcon = FileText
</script>

<template>
  <BaseCard title="最近学习" :subtitle="courseName ?? '当前课程'">
    <template #actions>
      <router-link class="card-link" :to="{ name: 'knowledge' }">
        查看全部 <ChevronRight :size="14" :stroke-width="1.9" />
      </router-link>
    </template>

    <div v-if="state.status === 'loading'" class="doc-skeleton" aria-hidden="true">
      <div v-for="index in 4" :key="index" class="doc-skeleton__row">
        <span class="doc-skeleton__icon" />
        <span class="doc-skeleton__text" />
      </div>
    </div>

    <div v-else-if="state.status === 'error'" class="module-error">
      <p>{{ state.error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>

    <EmptyState
      v-else-if="state.status === 'empty'"
      image="/illustrations/scene-study-desk.png"
      :title="
        authenticated ? (courseName === null ? '暂无课程' : '暂无资料') : '登录后查看资料'
      "
      :description="
        !authenticated
          ? '登录后即可查看当前课程的最近课件与解析状态。'
          : courseName === null
            ? '创建课程并上传课件后，最近的文档会显示在这里。'
            : '当前课程还没有课件，上传 PDF / MD / TXT 后即可检索。'
      "
      compact
    >
      <template #action>
        <router-link
          class="card-link"
          :to="{
            name: authenticated ? (courseName === null ? 'courses' : 'knowledge') : 'login',
          }"
        >
          {{ authenticated ? (courseName === null ? '新建课程' : '上传课件') : '登录 / 注册' }}
        </router-link>
      </template>
    </EmptyState>

    <ul v-else class="doc-list">
      <li v-for="doc in state.data" :key="doc.doc_id">
        <router-link
          class="doc-row"
          :to="{ name: 'knowledge' }"
          @click="selectCourse(doc)"
        >
          <span class="doc-row__icon">
            <component :is="fileIcon" :size="16" :stroke-width="1.7" />
          </span>
          <span class="doc-row__main">
            <strong>{{ doc.doc_name }}</strong>
            <small>{{ doc.file_ext }} · {{ relativeTimeLabel(doc.updated_at, nowMs) }}</small>
          </span>
          <TagBadge :label="documentStatusLabel(doc.status)" :tone="toneFor(doc)" />
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

.doc-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.doc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
  transition: background-color 0.18s ease;
}

.doc-row:hover {
  background: var(--sc-surface-hover);
}

.doc-row__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.doc-row__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.doc-row__main strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-row__main small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
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

.doc-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.doc-skeleton__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
}

.doc-skeleton__icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--sc-surface-muted);
}

.doc-skeleton__text {
  flex: 1;
  height: 12px;
  border-radius: 6px;
  background: var(--sc-surface-muted);
}
</style>
