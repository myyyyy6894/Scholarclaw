<script setup lang="ts">
import { computed } from 'vue'

import { ChevronRight } from '@lucide/vue'

import type { CourseNamespace } from '@/api/course'
import CourseCard from '@/components/course/CourseCard.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { ModuleState } from '@/views/home/use-home-data'

/**
 * 首页我的课程（P2）。
 *
 * 取前 4 门课程；每门课的 DDL 数来自同一份 DDL 看板按 `course_id` 聚合，
 * 文档数取课程统计（READY + PARSING + FAILED）。
 */
const props = defineProps<{
  state: ModuleState<CourseNamespace[]>
  /** `null` 表示 DDL 看板尚未就绪，卡片显示「—」而不是伪造 0。 */
  ddlCounts: Record<string, number> | null
  authenticated: boolean
}>()

defineEmits<{ retry: [] }>()

const TONES = ['blue', 'green', 'purple', 'orange'] as const
const showCourses = computed(() => props.state.data.slice(0, 4))

function ddlCountFor(courseId: string): number | null {
  return props.ddlCounts === null ? null : (props.ddlCounts[courseId] ?? 0)
}
</script>

<template>
  <BaseCard title="我的课程" subtitle="资料就绪度与课程空间">
    <template #actions>
      <router-link class="card-link" :to="{ name: 'courses' }">
        查看全部 <ChevronRight :size="14" :stroke-width="1.9" />
      </router-link>
    </template>

    <div v-if="state.status === 'loading'" class="course-skeleton" aria-hidden="true">
      <div v-for="index in 3" :key="index" class="course-skeleton__row">
        <span class="course-skeleton__icon" />
        <span class="course-skeleton__text" />
      </div>
    </div>

    <div v-else-if="state.status === 'error'" class="module-error">
      <p>{{ state.error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>

    <EmptyState
      v-else-if="state.status === 'empty'"
      image="/illustrations/scene-study-desk.png"
      :title="authenticated ? '还没有课程' : '登录后查看课程'"
      :description="
        authenticated
          ? '创建课程空间并上传课件后，这里会显示每门课的资料就绪度。'
          : '登录后即可管理课程空间与资料就绪度。'
      "
      compact
    >
      <template #action>
        <router-link class="card-link" :to="{ name: authenticated ? 'courses' : 'login' }">
          {{ authenticated ? '新建课程' : '登录 / 注册' }}
        </router-link>
      </template>
    </EmptyState>

    <div v-else class="course-list">
      <CourseCard
        v-for="(course, index) in showCourses"
        :key="course.course_id"
        :course="course"
        :ddl-count="ddlCountFor(course.course_id)"
        :tone="TONES[index % TONES.length]"
        compact
      />
    </div>
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

.course-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.course-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.course-skeleton__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
}

.course-skeleton__icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--sc-surface-muted);
}

.course-skeleton__text {
  flex: 1;
  height: 14px;
  border-radius: 7px;
  background: var(--sc-surface-muted);
}
</style>
