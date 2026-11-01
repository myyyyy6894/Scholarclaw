<script setup lang="ts">
import { computed, ref } from 'vue'

import { ChevronLeft, ChevronRight } from '@lucide/vue'

import type { DdlTask, DdlTaskStatus } from '@/api/ddl'
import BaseCard from '@/components/ui/BaseCard.vue'
import {
  buildCalendarCells,
  monthLabel,
  type CalendarCell,
} from '@/views/home/dashboard-data'
import { statusLabel, statusTagType } from '@/views/ddl/ddl-task'

/**
 * DDL 月历（P2 首页 / P4 DDL 页共用）。
 *
 * 只按后端 `due_date` 聚合，纯前端计算；无任务的日期不显示标记。
 * 点击某天在卡片内展开当天任务，并向父组件抛出 `select` 事件，
 * 便于 DDL 页复用为“按日期过滤”。
 */
const props = withDefaults(
  defineProps<{
    tasks: DdlTask[]
    todayIso: string
    loading?: boolean
    error?: string
    authenticated?: boolean
    /** DDL 页需要在自己的区域展示完整当天列表时可关闭内置三行摘要。 */
    showDayList?: boolean
  }>(),
  { loading: false, error: '', authenticated: true, showDayList: true },
)

const emit = defineEmits<{ select: [date: string] }>()

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'] as const

const todayParts = props.todayIso.split('-').map((part) => Number(part))
const initialYear = Number.isFinite(todayParts[0]) ? (todayParts[0] ?? 2026) : new Date().getFullYear()
const initialMonth = Number.isFinite(todayParts[1]) ? (todayParts[1] ?? 1) : new Date().getMonth() + 1

const viewYear = ref(initialYear)
const viewMonth = ref(initialMonth)
const selectedDate = ref<string | null>(null)

const cells = computed(() =>
  buildCalendarCells(props.tasks, viewYear.value, viewMonth.value, props.todayIso),
)
const monthTitle = computed(() => monthLabel(viewYear.value, viewMonth.value))
const monthTaskCount = computed(() =>
  cells.value.reduce((sum, cell) => sum + (cell.inMonth ? cell.tasks.length : 0), 0),
)
const selectedTasks = computed<DdlTask[]>(() => {
  if (selectedDate.value === null) {
    return []
  }
  return cells.value.find((cell) => cell.date === selectedDate.value)?.tasks ?? []
})
const isCurrentMonth = computed(
  () => viewYear.value === initialYear && viewMonth.value === initialMonth,
)

type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

function toneFor(status: DdlTaskStatus): Tone {
  const tone = statusTagType(status)
  return tone === 'info' ? 'neutral' : tone
}

function moveMonth(delta: number): void {
  const target = new Date(Date.UTC(viewYear.value, viewMonth.value - 1 + delta, 1))
  viewYear.value = target.getUTCFullYear()
  viewMonth.value = target.getUTCMonth() + 1
  selectedDate.value = null
}

function goToday(): void {
  viewYear.value = initialYear
  viewMonth.value = initialMonth
  selectedDate.value = props.todayIso
  emit('select', props.todayIso)
}

function selectDay(cell: CalendarCell): void {
  if (!cell.inMonth) {
    const [year, month] = cell.date.split('-').map((part) => Number(part))
    if (year !== undefined && month !== undefined) {
      viewYear.value = year
      viewMonth.value = month
    }
  }
  selectedDate.value = cell.date
  emit('select', cell.date)
}

function timeText(task: DdlTask): string {
  return task.due_time === null ? '全天' : task.due_time.slice(0, 5)
}
</script>

<template>
  <BaseCard title="DDL 日历" subtitle="按截止日期聚合">
    <template #actions>
      <div class="cal-nav">
        <button type="button" aria-label="上个月" @click="moveMonth(-1)">
          <ChevronLeft :size="15" :stroke-width="1.8" />
        </button>
        <span class="cal-nav__label">{{ monthTitle }}</span>
        <button type="button" aria-label="下个月" @click="moveMonth(1)">
          <ChevronRight :size="15" :stroke-width="1.8" />
        </button>
      </div>
    </template>

    <div class="cal__weekdays" aria-hidden="true">
      <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
    </div>

    <div class="cal__grid">
      <button
        v-for="cell in cells"
        :key="cell.date"
        type="button"
        class="cal__day"
        :class="{
          'cal__day--out': !cell.inMonth,
          'cal__day--today': cell.isToday,
          'cal__day--selected': cell.date === selectedDate,
        }"
        :aria-label="`${cell.date}，${cell.tasks.length} 个 DDL`"
        @click="selectDay(cell)"
      >
        <span class="cal__num">{{ cell.day }}</span>
        <span v-if="cell.tasks.length > 0" class="cal__count">{{ cell.tasks.length }}</span>
      </button>
    </div>

    <div class="cal__footer">
      <div class="cal__footer-head">
        <span>
          {{
            !authenticated
              ? '登录后查看 DDL 日历'
              : selectedDate === null
                ? '点击日期查看当天任务'
                : selectedDate
          }}
        </span>
        <button v-if="!isCurrentMonth" type="button" class="cal__today" @click="goToday">
          回到今天
        </button>
      </div>

      <template v-if="showDayList">
        <ul v-if="authenticated && selectedTasks.length > 0" class="cal__tasks">
          <li v-for="task in selectedTasks.slice(0, 3)" :key="task.task_id" class="cal__task">
            <span class="cal__dot" :class="`cal__dot--${toneFor(task.status)}`" />
            <span class="cal__task-title">{{ task.task_title }}</span>
            <span class="cal__task-meta">{{ timeText(task) }} · {{ task.course_name }}</span>
            <span class="cal__task-status">{{ statusLabel(task.status) }}</span>
          </li>
        </ul>
        <p v-else-if="!authenticated" class="cal__empty">登录后即可查看按截止日期聚合的 DDL。</p>
        <p v-else-if="loading" class="cal__empty">正在读取 DDL…</p>
        <p v-else-if="error !== ''" class="cal__empty cal__empty--error">{{ error }}</p>
        <p v-else-if="selectedDate !== null" class="cal__empty">当天没有 DDL 任务。</p>
        <p v-else-if="monthTaskCount === 0" class="cal__empty">本月暂无 DDL。</p>
      </template>
    </div>
  </BaseCard>
</template>

<style scoped>
.cal-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.cal-nav button {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--sc-border);
  border-radius: 7px;
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
  cursor: pointer;
}

.cal-nav button:hover {
  border-color: var(--sc-primary);
  color: var(--sc-primary);
}

.cal-nav__label {
  min-width: 76px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  text-align: center;
  white-space: nowrap;
}

.cal__weekdays {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
  width: 100%;
  max-width: 720px;
  margin: 0 auto 6px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  text-align: center;
}

.cal__grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
}

.cal__day {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  min-height: 38px;
  padding: 2px 0;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--sc-text-secondary);
  background: var(--sc-surface-muted);
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-caption);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}

.cal__day:hover {
  border-color: var(--sc-border);
  background: var(--sc-surface-hover);
}

.cal__day--out {
  color: var(--sc-text-placeholder);
  background: transparent;
  opacity: 0.5;
}

.cal__day--today {
  border-color: var(--sc-primary);
  color: var(--sc-primary-strong);
  font-weight: 600;
}

.cal__day--selected {
  background: var(--sc-primary);
  color: #fff;
  opacity: 1;
}

.cal__num {
  line-height: 1.2;
}

.cal__count {
  min-width: 14px;
  padding: 0 3px;
  border-radius: var(--sc-radius-pill);
  background: var(--sc-primary-soft);
  color: var(--sc-primary-strong);
  font-size: 9px;
  line-height: 13px;
}

.cal__day--selected .cal__count {
  background: rgba(255, 255, 255, 0.24);
  color: #fff;
}

.cal__footer {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--sc-border);
}

.cal__footer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.cal__today {
  padding: 0;
  border: 0;
  color: var(--sc-primary);
  background: transparent;
  font-size: var(--sc-font-hint);
  cursor: pointer;
}

.cal__tasks {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.cal__task {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: var(--sc-font-hint);
}

.cal__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.cal__dot--primary {
  background: var(--sc-primary);
}

.cal__dot--success {
  background: var(--sc-success);
}

.cal__dot--warning {
  background: var(--sc-warning);
}

.cal__dot--danger {
  background: var(--sc-danger);
}

.cal__dot--neutral {
  background: var(--sc-text-placeholder);
}

.cal__task-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--sc-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cal__task-meta {
  flex: none;
  color: var(--sc-text-placeholder);
  font-size: 9px;
}

.cal__task-status {
  flex: none;
  color: var(--sc-text-secondary);
  font-size: 9px;
}

.cal__empty {
  margin-top: 8px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.cal__empty--error {
  color: var(--sc-danger);
}
</style>
