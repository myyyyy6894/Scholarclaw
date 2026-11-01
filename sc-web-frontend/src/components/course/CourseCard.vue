<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'

import {
  BookOpen,
  CalendarClock,
  ChevronRight,
  Code2,
  Cpu,
  Database,
  FileText,
  Network,
} from '@lucide/vue'

import type { CourseNamespace } from '@/api/course'
import ProgressBar from '@/components/ui/ProgressBar.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { useCourseStore } from '@/stores/course'
import { indexStatusLabel } from '@/views/course/course-form'
import { courseReadiness } from '@/views/home/dashboard-data'

/**
 * 课程卡片（P2 首页 / P3 课程页共用）。
 *
 * 进度口径固定为「资料就绪度」= READY / 文档总数；文档总数为 0 时由
 * `ProgressBar` 显示「暂无资料」，不显示 0%。
 */
type Tone = 'blue' | 'green' | 'purple' | 'orange' | 'pink'

const props = withDefaults(
  defineProps<{
    course: CourseNamespace
    ddlCount?: number | null
    compact?: boolean
    tone?: Tone
  }>(),
  { ddlCount: null, compact: false, tone: 'blue' },
)

const TONE_ICONS: Record<Tone, Component> = {
  blue: Code2,
  green: Database,
  purple: Network,
  orange: Cpu,
  pink: BookOpen,
}

const courseStore = useCourseStore()
const icon = computed(() => TONE_ICONS[props.tone])
const readiness = computed(() => courseReadiness(props.course))
const indexTone = computed(() => {
  if (props.course.index_status === 'ACTIVE') {
    return 'success' as const
  }
  if (props.course.index_status === 'REBUILD_FAILED') {
    return 'danger' as const
  }
  return 'warning' as const
})

function selectCourse(): void {
  courseStore.select(props.course.course_id)
}
</script>

<template>
  <router-link
    class="course-card"
    :class="[`course-card--${tone}`, { 'course-card--compact': compact }]"
    :to="{ name: 'knowledge' }"
    @click="selectCourse"
  >
    <div class="course-card__top">
      <span class="course-card__icon">
        <component :is="icon" :size="19" :stroke-width="1.8" />
      </span>
      <span class="course-card__title">
        <strong>{{ course.course_name }}</strong>
        <small v-if="!compact && course.description">{{ course.description }}</small>
      </span>
      <TagBadge :label="indexStatusLabel(course.index_status)" :tone="indexTone" />
    </div>

    <ProgressBar
      :value="readiness.percent"
      label="资料就绪度"
      :tone="readiness.percent === 100 ? 'success' : 'primary'"
    />

    <div v-if="!compact" class="course-card__meta">
      <span>{{ course.embedding_model }} · {{ course.embedding_dim }} 维</span>
      <span>索引 v{{ course.index_version }}</span>
    </div>

    <div class="course-card__foot">
      <span><FileText class="course-card__foot-icon" :size="13" />文档 {{ readiness.total }}</span>
      <span>
        <CalendarClock class="course-card__foot-icon" :size="13" />DDL
        {{ ddlCount === null ? '—' : ddlCount }}
      </span>
      <span class="course-card__more">查看<ChevronRight :size="13" :stroke-width="2" /></span>
    </div>
  </router-link>
</template>

<style scoped>
.course-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.course-card:hover,
.course-card:focus-visible {
  border-color: var(--sc-primary);
  box-shadow: var(--sc-shadow);
  outline: none;
  transform: translateY(-1px);
}

.course-card__top {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.course-card__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
}

.course-card--blue .course-card__icon {
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.course-card--green .course-card__icon {
  color: var(--sc-success);
  background: var(--sc-success-soft);
}

.course-card--purple .course-card__icon {
  color: var(--sc-accent);
  background: var(--sc-accent-soft);
}

.course-card--orange .course-card__icon {
  color: var(--sc-warning);
  background: var(--sc-warning-soft);
}

.course-card--pink .course-card__icon {
  color: #db2777;
  background: #fdf2f8;
}

.course-card__title {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.course-card__title strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-card__title small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.course-card__meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.course-card__foot {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: 9px;
  border-top: 1px solid var(--sc-border);
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
}

.course-card__foot span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.course-card__foot-icon {
  flex: none;
  color: var(--sc-text-placeholder);
}

.course-card__more {
  margin-left: auto;
  color: var(--sc-primary);
}

.course-card--compact {
  padding: 10px 12px;
  border: 0;
  background: transparent;
}

.course-card--compact:hover {
  background: var(--sc-surface-hover);
  box-shadow: none;
  transform: none;
}

.course-card--compact .course-card__icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
}

.course-card--compact .course-card__foot {
  padding-top: 7px;
}
</style>
