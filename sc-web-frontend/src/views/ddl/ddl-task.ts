import type {
  DdlTask,
  DdlTaskBoard,
  DdlTaskStatus,
  DdlTimePrecision,
  TaskChangeDraft,
} from '@/api/ddl'
import type { BigIntId } from '@/api/types'

/**
 * DDL 工作台的纯展示 / 校验逻辑（`06` TASK-705）。
 *
 * 刻意不依赖 Vue 组件，便于单测覆盖以下硬性约定：
 *
 * * 模糊时间或缺失时间**不得**在 UI 上补造截止时间，只展示“未生成截止时间”；
 * * `DATE_ONLY` 必须显式标注“原通知未注明具体时刻，暂按当日 23:59 统计”；
 * * 编辑 / 新建表单严格对齐 `04` §4.6.2 / §4.6.4 的时间精度必填规则；
 * * 重复通知变更草案只做对比展示，是否覆盖由用户决策，前端不自行合并。
 */

export const MIN_RAW_TEXT = 4
export const MAX_RAW_TEXT = 4000
export const MAX_COURSE_NAME = 128
export const MAX_TITLE = 255
export const MAX_OPTIONAL_TEXT = 255

export type EditableTimePrecision = Exclude<DdlTimePrecision, 'INVALID_OR_PAST'>

export interface DdlTaskFormState {
  courseId: string
  courseName: string
  taskTitle: string
  timePrecision: EditableTimePrecision
  dueDate: string
  dueTime: string
  deliverableFormat: string
  submissionChannel: string
}

const STATUS_LABELS: Record<DdlTaskStatus, string> = {
  PENDING_TIME: '待补充时间',
  PENDING_CONFIRM: '待确认',
  ACTIVE: '进行中',
  OVERDUE: '已逾期',
  COMPLETED: '已完成',
  CANCELED: '已取消',
}

const PRECISION_LABELS: Record<DdlTimePrecision, string> = {
  EXACT_DATETIME: '精确到时刻',
  DATE_ONLY: '仅日期',
  VAGUE_OR_MISSING: '未明确时间',
  INVALID_OR_PAST: '非法或已过期',
}

export function statusLabel(status: DdlTaskStatus): string {
  return STATUS_LABELS[status] ?? status
}

export function statusTagType(
  status: DdlTaskStatus,
): 'primary' | 'warning' | 'danger' | 'success' | 'info' {
  switch (status) {
    case 'ACTIVE':
      return 'primary'
    case 'PENDING_TIME':
    case 'PENDING_CONFIRM':
      return 'warning'
    case 'OVERDUE':
      return 'danger'
    case 'COMPLETED':
      return 'success'
    default:
      return 'info'
  }
}

export function precisionLabel(precision: DdlTimePrecision): string {
  return PRECISION_LABELS[precision] ?? precision
}

export function isPendingStatus(status: DdlTaskStatus): boolean {
  return status === 'PENDING_TIME' || status === 'PENDING_CONFIRM'
}

/** 待确认收件箱里的任务为什么需要用户确认（不推测具体时间）。 */
export function pendingReason(task: Pick<DdlTask, 'status'>): string {
  return task.status === 'PENDING_TIME'
    ? '原通知未给出可用的截止时间，已归入待确认收件箱；在补充时间前不会触发 T-24h / T-6h / 逾期提醒。'
    : '时间存在跨周 / 凌晨歧义、置信度偏低或早于当前时间，请核对后确认截止时间以激活。'
}

/** 校验粘贴的通知原文（后端要求 `4 ~ 4000` 字符）。 */
export function rawTextError(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed.length < MIN_RAW_TEXT) {
    return `通知文本至少 ${MIN_RAW_TEXT} 个字符。`
  }
  if (trimmed.length > MAX_RAW_TEXT) {
    return `通知文本不得超过 ${MAX_RAW_TEXT} 个字符（当前 ${trimmed.length}）。`
  }
  return null
}

const UUID_FALLBACK_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/**
 * 生成请求级幂等 ID。
 *
 * 同一操作的重试必须复用同一个值；只有用户明确发起“仍然新建”时才生成新值
 * （`04` §4.6.1 核心幂等规则）。
 */
export function newClientRequestId(prefix = 'req_ddl'): string {
  const cryptoApi = typeof globalThis.crypto === 'undefined' ? null : globalThis.crypto
  if (cryptoApi !== null && typeof cryptoApi.randomUUID === 'function') {
    return `${prefix}_${cryptoApi.randomUUID()}`
  }
  const random = Math.random().toString(16).slice(2).padEnd(12, '0')
  return `${prefix}_${Date.now().toString(16)}-${random}`
}

/** 供测试断言用：不信任外部传入的 UUID 形状时，退化为长度检查。 */
export function isUuidLike(value: string): boolean {
  return UUID_FALLBACK_PATTERN.test(value)
}

function createFormatter(
  timezone: string | null,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  if (timezone !== null && timezone.trim() !== '') {
    try {
      return new Intl.DateTimeFormat('zh-CN', { ...options, timeZone: timezone })
    } catch {
      // 后端时区名异常时退回浏览器本地时区，绝不阻断页面渲染
    }
  }
  return new Intl.DateTimeFormat('zh-CN', options)
}

function partsOf(formatter: Intl.DateTimeFormat, date: Date): Record<string, string> {
  const parts: Record<string, string> = {}
  for (const part of formatter.formatToParts(date)) {
    parts[part.type] = part.value
  }
  return parts
}

function parseIso(value: string | null): Date | null {
  if (value === null || value.trim() === '') {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 把后端带偏移量的 `due_at` 按任务记录的用户时区展示，例如 `2026-10-16（周五）17:00`。
 *
 * 使用 `user_timezone` 而不是浏览器时区，保证与后端计算 `due_at` 的口径一致。
 */
export function formatDueAt(dueAt: string | null, timezone: string | null): string | null {
  const date = parseIso(dueAt)
  if (date === null) {
    return null
  }
  const formatter = createFormatter(timezone, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
  const parts = partsOf(formatter, date)
  const weekday = (parts.weekday ?? '').replace('星期', '周')
  return `${parts.year}-${parts.month}-${parts.day}（${weekday}）${parts.hour}:${parts.minute}`
}

/** 系统时间戳（`created_at` / `updated_at` / `completed_at`）的紧凑展示。 */
export function formatTimestamp(value: string | null): string | null {
  const date = parseIso(value)
  if (date === null) {
    return null
  }
  const parts = partsOf(
    createFormatter(null, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }),
    date,
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** `DATE_ONLY` 的推断说明；`time_inferred=false` 时返回 `null`，避免误导。 */
export function inferredNotice(task: Pick<DdlTask, 'time_inferred'>): string | null {
  return task.time_inferred
    ? '原通知未注明具体时刻，系统暂按当日 23:59 统计倒计时；可直接编辑补充具体时刻。'
    : null
}

/** 看板主时间列：没有 `due_at` 时明确显示“未生成”，不补造日期。 */
export function deadlineText(task: Pick<DdlTask, 'due_at' | 'user_timezone'>): string {
  return formatDueAt(task.due_at, task.user_timezone) ?? '未生成截止时间'
}

export function humanizeDuration(ms: number): string {
  const totalMinutes = Math.floor(Math.max(0, ms) / 60000)
  if (totalMinutes < 1) {
    return '不足 1 分钟'
  }
  if (totalMinutes < 60) {
    return `${totalMinutes} 分钟`
  }
  const totalHours = Math.floor(totalMinutes / 60)
  if (totalHours < 24) {
    const minutes = totalMinutes % 60
    return minutes === 0 ? `${totalHours} 小时` : `${totalHours} 小时 ${minutes} 分钟`
  }
  const days = Math.floor(totalHours / 24)
  const hours = totalHours % 24
  return hours === 0 ? `${days} 天` : `${days} 天 ${hours} 小时`
}

export interface Countdown {
  text: string
  overdue: boolean
}

/** 倒计时只作提示：`OVERDUE` 流转由调度任务负责（TASK-801），前端不改状态。 */
export function countdown(
  task: Pick<DdlTask, 'due_at' | 'status'>,
  nowMs: number,
): Countdown | null {
  if (task.due_at === null || task.status === 'COMPLETED' || task.status === 'CANCELED') {
    return null
  }
  const due = parseIso(task.due_at)
  if (due === null) {
    return null
  }
  const diff = due.getTime() - nowMs
  if (diff > 0) {
    return { text: `剩余 ${humanizeDuration(diff)}`, overdue: false }
  }
  return { text: `已超过 ${humanizeDuration(-diff)}`, overdue: true }
}

const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const CLOCK_TIME_PATTERN = /^(\d{2}):(\d{2})(?::(\d{2}))?$/

/** 公历合法性（拦截 `2026-02-29`、`2026-04-31`），与后端 `INVALID_CALENDAR_DATE` 同口径。 */
export function isValidCalendarDate(value: string): boolean {
  const match = CALENDAR_DATE_PATTERN.exec(value.trim())
  if (match === null) {
    return false
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return false
  }
  const utc = new Date(Date.UTC(year, month - 1, day))
  return (
    utc.getUTCFullYear() === year &&
    utc.getUTCMonth() === month - 1 &&
    utc.getUTCDate() === day
  )
}

export function isValidClockTime(value: string): boolean {
  const match = CLOCK_TIME_PATTERN.exec(value.trim())
  if (match === null) {
    return false
  }
  const hour = Number(match[1])
  const minute = Number(match[2])
  const second = match[3] === undefined ? 0 : Number(match[3])
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59
}

/** 统一成 `HH:mm:ss`，与 `DdlTaskVO.due_time` 的格式保持一致。 */
export function normalizeClockTime(value: string): string {
  const trimmed = value.trim()
  const match = CLOCK_TIME_PATTERN.exec(trimmed)
  if (match === null) {
    return trimmed
  }
  const hour = match[1]
  const minute = match[2]
  const second = match[3] ?? '00'
  return `${hour}:${minute}:${second}`
}

export function emptyTaskForm(): DdlTaskFormState {
  return {
    courseId: '',
    courseName: '',
    taskTitle: '',
    timePrecision: 'EXACT_DATETIME',
    dueDate: '',
    dueTime: '',
    deliverableFormat: '',
    submissionChannel: '',
  }
}

export function taskFormFrom(task: DdlTask): DdlTaskFormState {
  const precision: EditableTimePrecision =
    task.time_precision === 'INVALID_OR_PAST'
      ? task.due_date === null
        ? 'VAGUE_OR_MISSING'
        : 'DATE_ONLY'
      : task.time_precision
  return {
    courseId: task.course_id ?? '',
    courseName: task.course_name,
    taskTitle: task.task_title,
    timePrecision: precision,
    dueDate: task.due_date ?? '',
    dueTime: task.due_time === null ? '' : normalizeClockTime(task.due_time),
    deliverableFormat: task.deliverable_format ?? '',
    submissionChannel: task.submission_channel ?? '',
  }
}

/** 表单校验；返回 `null` 表示可以提交。 */
export function taskFormError(form: DdlTaskFormState): string | null {
  const courseName = form.courseName.trim()
  if (courseName === '') {
    return '请选择课程或填写课程名称。'
  }
  if (courseName.length > MAX_COURSE_NAME) {
    return `课程名称不得超过 ${MAX_COURSE_NAME} 个字符。`
  }
  const title = form.taskTitle.trim()
  if (title === '') {
    return '请填写任务标题。'
  }
  if (title.length > MAX_TITLE) {
    return `任务标题不得超过 ${MAX_TITLE} 个字符。`
  }
  if (form.timePrecision === 'EXACT_DATETIME' || form.timePrecision === 'DATE_ONLY') {
    if (form.dueDate.trim() === '') {
      return '当前时间精度需要填写截止日期。'
    }
    if (!isValidCalendarDate(form.dueDate)) {
      return '截止日期不是合法公历日期（格式 YYYY-MM-DD）。'
    }
  }
  if (form.timePrecision === 'EXACT_DATETIME') {
    if (form.dueTime.trim() === '') {
      return '精确到时刻的截止时间需要填写具体时刻。'
    }
    if (!isValidClockTime(form.dueTime)) {
      return '截止时刻格式应为 HH:mm 或 HH:mm:ss。'
    }
  }
  if (form.deliverableFormat.trim().length > MAX_OPTIONAL_TEXT) {
    return `交付物格式不得超过 ${MAX_OPTIONAL_TEXT} 个字符。`
  }
  if (form.submissionChannel.trim().length > MAX_OPTIONAL_TEXT) {
    return `提交渠道不得超过 ${MAX_OPTIONAL_TEXT} 个字符。`
  }
  return null
}

export interface TimeFields {
  due_date: string | null
  due_time: string | null
}

/** 按时间精度裁剪时间字段：`DATE_ONLY` 绝不夹带时刻，`VAGUE` 一律清空。 */
export function timeFields(form: DdlTaskFormState): TimeFields {
  if (form.timePrecision === 'VAGUE_OR_MISSING') {
    return { due_date: null, due_time: null }
  }
  const dueDate = form.dueDate.trim()
  if (form.timePrecision === 'DATE_ONLY') {
    return { due_date: dueDate, due_time: null }
  }
  return { due_date: dueDate, due_time: normalizeClockTime(form.dueTime) }
}

function optionalText(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/** 手动新建（`API-DDL-002`）请求体。 */
export function buildCreatePayload(
  form: DdlTaskFormState,
  clientRequestId: string,
): {
  client_request_id: string
  course_id: string | null
  course_name: string
  task_title: string
  time_precision: EditableTimePrecision
  due_date: string | null
  due_time: string | null
  deliverable_format: string | null
  submission_channel: string | null
} {
  const time = timeFields(form)
  return {
    client_request_id: clientRequestId,
    course_id: form.courseId.trim() === '' ? null : form.courseId.trim(),
    course_name: form.courseName.trim(),
    task_title: form.taskTitle.trim(),
    time_precision: form.timePrecision,
    due_date: time.due_date,
    due_time: time.due_time,
    deliverable_format: optionalText(form.deliverableFormat),
    submission_channel: optionalText(form.submissionChannel),
  }
}

/** 编辑 / 确认激活（`API-DDL-004`）请求体。 */
export function buildUpdatePayload(form: DdlTaskFormState): {
  course_id: string | null
  course_name: string
  task_title: string
  time_precision: EditableTimePrecision
  due_date: string | null
  due_time: string | null
  deliverable_format: string | null
  submission_channel: string | null
} {
  const time = timeFields(form)
  return {
    course_id: form.courseId.trim() === '' ? null : form.courseId.trim(),
    course_name: form.courseName.trim(),
    task_title: form.taskTitle.trim(),
    time_precision: form.timePrecision,
    due_date: time.due_date,
    due_time: time.due_time,
    deliverable_format: optionalText(form.deliverableFormat),
    submission_channel: optionalText(form.submissionChannel),
  }
}

export interface DraftDiffRow {
  label: string
  before: string
  after: string
  changed: boolean
}

function textOrDash(value: string | null): string {
  return value === null || value.trim() === '' ? '—' : value
}

/**
 * 重复通知变更草案的对比行。
 *
 * 只做展示，不判断“更优”；是否覆盖由用户点击决策按钮决定（`FR-DDL-001` 严禁静默覆盖）。
 */
export function draftDiffRows(task: DdlTask | null, draft: TaskChangeDraft): DraftDiffRow[] {
  const rows: Array<{ label: string; before: string; after: string }> = [
    { label: '任务标题', before: textOrDash(task?.task_title ?? null), after: textOrDash(draft.new_task_title) },
    {
      label: '时间精度',
      before: task === null ? '—' : precisionLabel(task.time_precision),
      after: precisionLabel(draft.new_time_precision),
    },
    { label: '截止日期', before: textOrDash(task?.due_date ?? null), after: textOrDash(draft.new_due_date) },
    {
      label: '截止时刻',
      before: textOrDash(task?.due_time ?? null),
      after: textOrDash(draft.new_due_time),
    },
    {
      label: '交付物格式',
      before: textOrDash(task?.deliverable_format ?? null),
      after: textOrDash(draft.new_deliverable_format),
    },
    {
      label: '提交渠道',
      before: textOrDash(task?.submission_channel ?? null),
      after: textOrDash(draft.new_submission_channel),
    },
  ]
  return rows.map((row) => ({
    ...row,
    changed: row.before.trim() !== row.after.trim(),
  }))
}

/** 草案有效期提示；过期后后端返回 `DRAFT_EXPIRED_OR_NOT_FOUND`。 */
export function draftExpiryText(draft: TaskChangeDraft): string | null {
  const expires = formatTimestamp(draft.expires_at)
  return expires === null ? null : `草案有效期至 ${expires}`
}

export function draftNewDeadline(draft: TaskChangeDraft): string {
  return formatDueAt(draft.new_due_at, null) ?? '未生成截止时间'
}

export function findTask(board: DdlTaskBoard, taskId: BigIntId): DdlTask | null {
  const all = [...board.pending_inbox_tasks, ...board.active_tasks, ...board.completed_tasks]
  return all.find((task) => task.task_id === taskId) ?? null
}

export interface BoardCounts {
  pending: number
  drafts: number
  active: number
  overdue: number
  completed: number
}

export function boardCounts(board: DdlTaskBoard): BoardCounts {
  return {
    pending: board.pending_inbox_tasks.length,
    drafts: board.pending_change_drafts.length,
    active: board.active_tasks.filter((task) => task.status === 'ACTIVE').length,
    overdue: board.active_tasks.filter((task) => task.status === 'OVERDUE').length,
    completed: board.completed_tasks.length,
  }
}

export function emptyBoard(): DdlTaskBoard {
  return {
    pending_inbox_tasks: [],
    pending_change_drafts: [],
    active_tasks: [],
    completed_tasks: [],
  }
}
