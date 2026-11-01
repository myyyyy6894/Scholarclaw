import type { CourseNamespace } from '@/api/course'
import type { DdlTask, DdlTaskBoard } from '@/api/ddl'
import type { KnowledgeDoc } from '@/api/knowledge'
import type { NotificationItem } from '@/api/notifications'
import type { ChatMessage } from '@/api/rag'

/**
 * 首页 Dashboard 的纯逻辑（P2）。
 *
 * 这里只做确定性计算，不发请求、不碰 DOM，便于 node 环境单测：
 * 资料就绪度、DDL 排序与分组、日历网格、最近文档/问答、通知合并、问候与时区日期。
 *
 * 口径铁律：
 * * **资料就绪度** = READY / 文档总数；总数为 0 时返回 `null`，由组件显示「暂无资料」；
 * * **DDL 日历** 只按后端 `due_date`（YYYY-MM-DD）聚合，不用浏览器 `Date` 二次换算；
 * * **今日/问候** 按用户时区用 `Intl` 计算，默认 `Asia/Shanghai`。
 */

export const DEFAULT_TIMEZONE = 'Asia/Shanghai'

const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

export interface Readiness {
  ready: number
  total: number
  /** `null` 表示没有文档，禁止显示 0%。 */
  percent: number | null
}

export interface ZonedDateParts {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  /** 0 = 周日，1 = 周一，…… */
  weekday: number
}

export interface CalendarCell {
  /** `YYYY-MM-DD`，与后端 `due_date` 同格式。 */
  date: string
  day: number
  inMonth: boolean
  isToday: boolean
  tasks: DdlTask[]
}

export interface ChatPair {
  user: ChatMessage
  assistant: ChatMessage
}

export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? error.message : fallback
}

/** 资料就绪度；文档数为 0 时 `percent` 为 `null`。 */
export function courseReadiness(
  course: Pick<CourseNamespace, 'ready_doc_count' | 'parsing_doc_count' | 'failed_doc_count'>,
): Readiness {
  const ready = Math.max(0, course.ready_doc_count)
  const total =
    ready + Math.max(0, course.parsing_doc_count) + Math.max(0, course.failed_doc_count)
  if (total === 0) {
    return { ready: 0, total: 0, percent: null }
  }
  return {
    ready,
    total,
    percent: Math.max(0, Math.min(100, Math.round((ready / total) * 100))),
  }
}

function parseTimestamp(value: string | null): number | null {
  if (value === null || value === '') {
    return null
  }
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}

/** 进行中任务按截止时间升序；`due_at` 缺失的排在最后。 */
export function sortActiveTasks(board: DdlTaskBoard): DdlTask[] {
  return [...board.active_tasks]
    .filter((task) => task.status !== 'COMPLETED' && task.status !== 'CANCELED')
    .sort((left, right) => {
      const leftDue = parseTimestamp(left.due_at)
      const rightDue = parseTimestamp(right.due_at)
      if (leftDue === null && rightDue === null) {
        return left.created_at.localeCompare(right.created_at)
      }
      if (leftDue === null) {
        return 1
      }
      if (rightDue === null) {
        return -1
      }
      return leftDue - rightDue
    })
}

export function todayTasks(board: DdlTaskBoard, limit = 5): DdlTask[] {
  return sortActiveTasks(board).slice(0, limit)
}

/** 看板里所有未被取消的任务按课程聚合；同一任务只计一次。 */
export function groupDdlCountByCourse(board: DdlTaskBoard): Record<string, number> {
  const seen = new Set<string>()
  const counts: Record<string, number> = {}
  const groups = [
    board.pending_inbox_tasks,
    board.active_tasks,
    board.completed_tasks,
  ]
  for (const group of groups) {
    for (const task of group) {
      if (task.course_id === null || seen.has(task.task_id)) {
        continue
      }
      seen.add(task.task_id)
      counts[task.course_id] = (counts[task.course_id] ?? 0) + 1
    }
  }
  return counts
}

/** 最近学习：排除已删除/删除中，按更新时间降序。 */
export function recentDocs(docs: KnowledgeDoc[], limit = 5): KnowledgeDoc[] {
  return docs
    .filter((doc) => doc.status !== 'DELETED' && doc.status !== 'DELETING')
    .sort((left, right) => {
      const leftTime = parseTimestamp(left.updated_at) ?? 0
      const rightTime = parseTimestamp(right.updated_at) ?? 0
      return rightTime - leftTime
    })
    .slice(0, limit)
}

/**
 * 最近问答：从尾部向前配对 USER + 紧随其后的 ASSISTANT。
 * 返回顺序为“最新在前”，最多 `limit` 对。
 */
export function recentChatPairs(messages: ChatMessage[], limit = 4): ChatPair[] {
  const ordered = [...messages].sort((left, right) => {
    const leftTime = parseTimestamp(left.created_at) ?? 0
    const rightTime = parseTimestamp(right.created_at) ?? 0
    return leftTime - rightTime
  })
  const pairs: ChatPair[] = []
  let index = ordered.length - 1
  while (index >= 0 && pairs.length < limit) {
    const assistant = ordered[index]
    if (assistant === undefined || assistant.role !== 'ASSISTANT') {
      index -= 1
      continue
    }
    let user: ChatMessage | null = null
    for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
      const candidate = ordered[cursor]
      if (candidate === undefined) {
        continue
      }
      if (candidate.role === 'USER') {
        user = candidate
        break
      }
      if (candidate.role === 'ASSISTANT') {
        break
      }
    }
    if (user !== null) {
      pairs.push({ user, assistant })
      index = ordered.indexOf(user) - 1
    } else {
      index -= 1
    }
  }
  return pairs
}

/** 未读优先合并：先去重，再按“未读通知 → 其余通知”的顺序截断。 */
export function mergeNoticeItems(
  all: NotificationItem[],
  unread: NotificationItem[],
  limit = 4,
): NotificationItem[] {
  const seen = new Set<string>()
  const merged: NotificationItem[] = []
  for (const item of [...unread, ...all]) {
    if (seen.has(item.notification_id)) {
      continue
    }
    seen.add(item.notification_id)
    merged.push(item)
    if (merged.length >= limit) {
      break
    }
  }
  return merged
}

export function greetingForHour(hour: number): string {
  if (hour >= 5 && hour <= 8) {
    return '早上好'
  }
  if (hour >= 9 && hour <= 11) {
    return '上午好'
  }
  if (hour >= 12 && hour <= 13) {
    return '中午好'
  }
  if (hour >= 14 && hour <= 17) {
    return '下午好'
  }
  if (hour >= 18 && hour <= 22) {
    return '晚上好'
  }
  return '夜深了'
}

export function safeTimeZone(timeZone: string): string {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone }).format(new Date())
    return timeZone
  } catch {
    return DEFAULT_TIMEZONE
  }
}

/** 用 `Intl` 取指定时区的墙钟，不依赖宿主时区。 */
export function zonedDateParts(date: Date, timeZone: string): ZonedDateParts {
  const zone = safeTimeZone(timeZone)
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    weekday: 'short',
  })
  const parts = formatter.formatToParts(date)
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '0'
  const weekdayMap: Record<string, number> = {
    Sun: 0,
    Mon: 1,
    Tue: 2,
    Wed: 3,
    Thu: 4,
    Fri: 5,
    Sat: 6,
  }
  const hour = Number(pick('hour')) % 24
  return {
    year: Number(pick('year')),
    month: Number(pick('month')),
    day: Number(pick('day')),
    hour,
    minute: Number(pick('minute')),
    weekday: weekdayMap[pick('weekday')] ?? 0,
  }
}

export function formatDateLabel(parts: ZonedDateParts): string {
  return `${parts.year}年${parts.month}月${parts.day}日 ${WEEKDAY_LABELS[parts.weekday] ?? ''}`.trim()
}

export function toDateString(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

export function monthLabel(year: number, month: number): string {
  return `${year}年${month}月`
}

/**
 * 月视图网格：周一为第一列，固定 6 行（42 格），含上下月补位。
 * 使用 UTC 做日历运算，避免宿主时区把日期挪一天。
 */
export function buildMonthGrid(year: number, month: number, todayIso = ''): CalendarCell[] {
  const first = new Date(Date.UTC(year, month - 1, 1))
  const offset = (first.getUTCDay() + 6) % 7
  const start = Date.UTC(year, month - 1, 1 - offset)
  const cells: CalendarCell[] = []
  for (let index = 0; index < 42; index += 1) {
    const date = new Date(start + index * 86_400_000)
    const cellYear = date.getUTCFullYear()
    const cellMonth = date.getUTCMonth() + 1
    const cellDay = date.getUTCDate()
    const iso = toDateString(cellYear, cellMonth, cellDay)
    cells.push({
      date: iso,
      day: cellDay,
      inMonth: cellMonth === month && cellYear === year,
      isToday: iso === todayIso,
      tasks: [],
    })
  }
  return cells
}

/** 只按后端 `due_date` 聚合；没有截止日期的任务不进入日历。 */
export function groupTasksByDate(tasks: DdlTask[]): Map<string, DdlTask[]> {
  const grouped = new Map<string, DdlTask[]>()
  for (const task of tasks) {
    if (task.due_date === null || task.due_date === '') {
      continue
    }
    const bucket = grouped.get(task.due_date)
    if (bucket === undefined) {
      grouped.set(task.due_date, [task])
    } else {
      bucket.push(task)
    }
  }
  return grouped
}

export function buildCalendarCells(
  tasks: DdlTask[],
  year: number,
  month: number,
  todayIso = '',
): CalendarCell[] {
  const grouped = groupTasksByDate(tasks)
  return buildMonthGrid(year, month, todayIso).map((cell) => ({
    ...cell,
    tasks: grouped.get(cell.date) ?? [],
  }))
}

/** 首页用的紧凑截止时间：`截止 10-30 23:59`，不重复显示年份与星期。 */
export function compactDeadline(
  task: Pick<DdlTask, 'due_date' | 'due_time'>,
): string {
  if (task.due_date === null) {
    return '未生成截止时间'
  }
  const date = task.due_date.slice(5)
  const time = task.due_time === null ? '' : ` ${task.due_time.slice(0, 5)}`
  return `截止 ${date}${time}`
}

/** 相对时间：小于 7 天用中文相对量，更早直接显示后端日期部分。 */
export function relativeTimeLabel(iso: string | null, nowMs: number): string {
  if (iso === null || iso === '') {
    return ''
  }
  const timestamp = parseTimestamp(iso)
  if (timestamp === null) {
    return ''
  }
  const diff = Math.max(0, nowMs - timestamp)
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  const days = Math.floor(hours / 24)
  if (days < 7) {
    return `${days} 天前`
  }
  return iso.slice(0, 10)
}

export function pickDefaultCourse(
  courses: CourseNamespace[],
  selectedCourseId: string | null,
): CourseNamespace | null {
  return (
    courses.find((course) => course.course_id === selectedCourseId) ?? courses[0] ?? null
  )
}
