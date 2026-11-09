<script setup lang="ts">
import { CalendarDays, ChevronRight } from '@lucide/vue'
import type { ScheduleOccurrence, ScheduleTerm } from '@/api/schedule'
import BaseCard from '@/components/ui/BaseCard.vue'
import type { ModuleState } from '@/views/home/use-home-data'
import { formatClock, shiftDate } from '@/views/schedule/schedule-date'

const props = defineProps<{
  state: ModuleState<ScheduleOccurrence[]>
  term: ScheduleTerm | null
  date: string
  nowMs: number
  authenticated: boolean
}>()
defineEmits<{ retry: [] }>()

function time(value: string): string {
  return props.term ? formatClock(value, props.term.timezone) : ''
}
function status(event: ScheduleOccurrence): string {
  const start = Date.parse(event.start_at)
  const end = Date.parse(event.end_at)
  if (props.nowMs >= start && props.nowMs < end) return '进行中'
  if (props.nowMs < start) return '未开始'
  return '已结束'
}
function emptyMessage(): string {
  if (!props.authenticated) return '登录后查看今日课程'
  if (!props.term) return '还没有当前学期'
  if (props.date < props.term.first_monday) return '学期尚未开始'
  if (props.date > shiftDate(props.term.first_monday, props.term.week_count * 7 - 1)) return '教学周已结束'
  return '今天没有课程'
}
</script>

<template>
  <BaseCard title="今日课程" :subtitle="term ? `${date} · ${term.timezone}` : '按当前学期查看'">
    <template #actions>
      <router-link class="card-link" :to="authenticated ? { name: 'schedule', query: date ? { date } : {} } : { name: 'login' }">
        查看课表 <ChevronRight :size="14" />
      </router-link>
    </template>
    <p v-if="state.status === 'loading'" class="schedule-message">正在读取今日课程…</p>
    <div v-else-if="state.status === 'error'" class="schedule-message" role="alert">
      <p>{{ state.error }}</p><el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>
    <div v-else-if="state.status === 'empty'" class="schedule-message">
      <CalendarDays :size="24" :stroke-width="1.6" />
      <p>{{ emptyMessage() }}</p>
      <router-link class="card-link" :to="{ name: authenticated ? 'schedule' : 'login' }">
        {{ authenticated ? '前往课表设置' : '登录 / 注册' }}
      </router-link>
    </div>
    <ul v-else class="today-courses">
      <li v-for="event in state.data" :key="event.key">
        <router-link :to="{ name: 'schedule', query: { date } }">
          <span class="today-courses__time">{{ time(event.start_at) }}<small>{{ time(event.end_at) }} 结束</small></span>
          <span class="today-courses__main"><strong>{{ event.title }}</strong><small>{{ event.room || '教室未填写' }} · {{ event.teacher || '教师未填写' }}</small></span>
          <span class="today-courses__status" :class="{ 'is-conflict': event.conflict }">{{ event.conflict ? '时间重叠' : status(event) }}</span>
        </router-link>
      </li>
    </ul>
  </BaseCard>
</template>

<style scoped>
.card-link { display: inline-flex; align-items: center; color: var(--sc-primary); font-size: var(--sc-font-caption); text-decoration: none; }
.schedule-message { padding: 14px 0; color: var(--sc-text-secondary); font-size: var(--sc-font-body); }
.schedule-message p { margin: 8px 0; }
.today-courses { margin: 0; padding: 0; list-style: none; }
.today-courses a { display: flex; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--sc-border); color: inherit; text-decoration: none; }
.today-courses li:last-child a { border-bottom: 0; }
.today-courses__time { min-width: 65px; color: var(--sc-primary-strong); font-weight: 600; }
.today-courses__main { flex: 1; min-width: 0; }
.today-courses small { display: block; margin-top: 4px; color: var(--sc-text-secondary); font-size: var(--sc-font-caption); font-weight: 400; }
.today-courses__status { color: var(--sc-text-secondary); font-size: var(--sc-font-caption); white-space: nowrap; }
.today-courses__status.is-conflict { color: var(--sc-danger); }
</style>
