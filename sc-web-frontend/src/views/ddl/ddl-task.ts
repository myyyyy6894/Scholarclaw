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

/** 编辑弹窗展示的原任务时间语义快照。 */
export interface OriginalTimeInfo {
  precision: string
  deadline: string
  reason: string | null
  timezone: string
  /** 原时间是否属于“非法或已过期/待确认”，需要用户显式重新确认。 */
  needsReconfirm: boolean
}

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
    ? '原通知未给出可用的截止时间，已归入待确认收件箱；补充时间前不会进入正式提醒范围，也不会生成倒计时。'
    : '时间存在跨周 / 凌晨歧义、置信度偏低或早于当前时间，请核对后确认截止时间以激活。'
}

export interface PendingCardInfo {
  /** 解析后的公历日期 + 星期 + 时刻（用户时区）；未生成时明确说明。 */
  deadline: string
  rawTime: string | null
  /** 后端持久化的确认原因；缺失时回退到通用说明。 */
  reason: string
  /** 是否使用了通用说明（即后端没有给出具体原因）。 */
  genericReason: boolean
  /** `DATE_ONLY` 是否使用了 23:59 推断；待确认卡片必须明确说明。 */
  timeInferred: boolean
}

/**
 * 待确认卡片的展示事实。
 *
 * 必须同时给出“解析后的公历日期与星期”和“原文时间表述”，跨年任务由后端
 * `time_ambiguity_reason` 给出“已按跨年顺延至次年，请核对”，前端不得自行推断。
 */
export function pendingCardInfo(task: DdlTask): PendingCardInfo {
  const rawTime = task.raw_time_text === null ? null : task.raw_time_text.trim() || null
  const stored = task.time_ambiguity_reason === null
    ? null
    : task.time_ambiguity_reason.trim() || null
  return {
    deadline: deadlineText(task),
    rawTime,
    reason: stored ?? pendingReason(task),
    genericReason: stored === null,
    timeInferred: task.time_inferred,
  }
}

/** 编辑弹窗展示的原任务时间信息；用于避免“保存原字段”被误认为重新确认时间。 */
export function originalTimeInfo(task: DdlTask): OriginalTimeInfo {
  return {
    precision: precisionLabel(task.time_precision),
    deadline: deadlineText(task),
    reason: task.time_ambiguity_reason === null
      ? null
      : task.time_ambiguity_reason.trim() || null,
    timezone: task.user_timezone.trim() === '' ? '时区缺失（后端未返回）' : task.user_timezone,
    needsReconfirm:
      task.time_precision === 'INVALID_OR_PAST' || task.status === 'PENDING_CONFIRM',
  }
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

/** 产品默认时区（`01` FR-DDL-001）：缺失时区时必须显式标注，绝不静默用浏览器时区。 */
export const DEFAULT_DISPLAY_TIMEZONE = 'Asia/Shanghai'

interface DisplayZone {
  zone: string
  /** 展示用标签：正常时区名；缺失/非法时明确说明回退到默认时区。 */
  label: string
}

function displayZone(timezone: string | null): DisplayZone {
  const trimmed = timezone === null ? '' : timezone.trim()
  if (trimmed === '') {
    return { zone: DEFAULT_DISPLAY_TIMEZONE, label: '时区缺失，按 Asia/Shanghai 显示' }
  }
  try {
    // 提前校验 IANA 名称，非法时同样标注回退，而不是让 Intl 抛错或使用浏览器时区。
    new Intl.DateTimeFormat('zh-CN', { timeZone: trimmed })
    return { zone: trimmed, label: trimmed }
  } catch {
    return {
      zone: DEFAULT_DISPLAY_TIMEZONE,
      label: `时区无效（${trimmed}），按 Asia/Shanghai 显示`,
    }
  }
}

function createFormatter(
  timezone: string | null,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  // 任何情况下都必须显式指定 timeZone：不传会让 Intl 使用浏览器本地时区，
  // 同一任务在不同电脑上会显示成不同时刻。
  return new Intl.DateTimeFormat('zh-CN', {
    ...options,
    timeZone: displayZone(timezone).zone,
  })
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
 * 把后端带偏移量的 `due_at` 按任务记录的用户时区展示，
 * 例如 `2026-10-16（周五）17:00（Asia/Shanghai）`。
 *
 * 使用 `user_timezone` 而不是浏览器时区，保证与后端计算 `due_at` 的口径一致；
 * 时区缺失或非法时按产品默认 `Asia/Shanghai` 展示并明确标注。
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
  const zoneLabel = displayZone(timezone).label
  return `${parts.year}-${parts.month}-${parts.day}（${weekday}）${parts.hour}:${parts.minute}（${zoneLabel}）`
}

/** 系统时间戳（`created_at` / `updated_at` / `completed_at`）的紧凑展示。 */
export function formatTimestamp(value: string | null, timezone: string | null = null): string | null {
  const date = parseIso(value)
  if (date === null) {
    return null
  }
  const parts = partsOf(
    createFormatter(timezone, {
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
  // INVALID_OR_PAST 必须按“是否真的没有时刻”降级，不能一律当 DATE_ONLY：
  // 过去时间但原文有明确时刻时，编辑表单必须保留 EXACT_DATETIME，否则保存会把
  // 原 22:00 改成 DATE_ONLY 的 23:59:59 哨兵。
  const precision: EditableTimePrecision =
    task.time_precision === 'INVALID_OR_PAST'
      ? task.due_date === null
        ? 'VAGUE_OR_MISSING'
        : task.due_time === null
          ? 'DATE_ONLY'
          : 'EXACT_DATETIME'
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
export function buildUpdatePayload(form: DdlTaskFormState, reconfirmTime = false): {
  course_id: string | null
  course_name: string
  task_title: string
  time_precision: EditableTimePrecision
  due_date: string | null
  due_time: string | null
  deliverable_format: string | null
  submission_channel: string | null
  reconfirm_time: boolean
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
    reconfirm_time: reconfirmTime,
  }
}

/**
 * 一次“手动新建”操作的幂等身份。
 *
 * 与通知提取不同，手动建单只有一个提交按钮：超时重试时用户可能已经改了表单。
 * 只有**未修改表单**的重试才允许复用原 `client_request_id`；表单变化后必须换新 ID，
 * 否则服务端会把它当成“同一请求 ID 的不同内容”返回 409。
 */
export interface ManualCreateOperationState {
  requestId: string
  /** 上次提交时的表单指纹；未提交时为 `null`。 */
  fingerprint: string | null
  submitted: boolean
}

export function newManualCreateOperation(requestId: string): ManualCreateOperationState {
  return { requestId, fingerprint: null, submitted: false }
}

/** 手动建单请求指纹：排除 `client_request_id`，只用于判断表单内容是否变化。 */
export function manualCreateFingerprint(form: DdlTaskFormState): string {
  const payload = buildCreatePayload(form, '')
  return JSON.stringify({
    course_id: payload.course_id,
    course_name: payload.course_name,
    task_title: payload.task_title,
    time_precision: payload.time_precision,
    due_date: payload.due_date,
    due_time: payload.due_time,
    deliverable_format: payload.deliverable_format,
    submission_channel: payload.submission_channel,
  })
}

export interface ManualCreateSubmit {
  operation: ManualCreateOperationState
  requestId: string
  /** `true` 表示复用了上次提交的请求 ID（未修改表单的重试）。 */
  reused: boolean
}

/**
 * 计算本次手动建单应使用的请求 ID：
 *
 * * 首次提交：沿用打开弹窗时生成的 ID；
 * * 失败/超时后未修改表单重试：复用原 ID（同一次操作的幂等重试）；
 * * 已提交后修改表单：生成新的操作 ID，避免服务端返回 409 IDEMPOTENCY_CONFLICT。
 */
export function manualCreateSubmitState(
  operation: ManualCreateOperationState,
  form: DdlTaskFormState,
  nextRequestId: string,
): ManualCreateSubmit {
  const fingerprint = manualCreateFingerprint(form)
  if (!operation.submitted) {
    return {
      operation: { ...operation, fingerprint, submitted: true },
      requestId: operation.requestId,
      reused: false,
    }
  }
  if (operation.fingerprint === fingerprint) {
    return {
      operation: { ...operation, submitted: true },
      requestId: operation.requestId,
      reused: true,
    }
  }
  return {
    operation: { requestId: nextRequestId, fingerprint, submitted: true },
    requestId: nextRequestId,
    reused: false,
  }
}

/** 409 IDEMPOTENCY_CONFLICT：原 ID 已被其它内容占用，必须换新 ID 重新提交。 */
export function resetManualCreateAfterConflict(
  _operation: ManualCreateOperationState,
  nextRequestId: string,
): ManualCreateOperationState {
  return { requestId: nextRequestId, fingerprint: null, submitted: false }
}

export interface ExtractOperationState {
  requestId: string
  forceCreate: boolean
  submitted: boolean
  rawText: string
  courseId: string
}

/**
 * 一次“粘贴通知提取”操作的身份状态。
 *
 * 规则（`04` §4.6.1 CONFLICT-01 定稿）：
 *
 * * 同一操作超时重试必须复用 `requestId`；
 * * 已提交后用户改动原文/课程 → 生成新的 `requestId` 并重置 `forceCreate=false`；
 * * `forceCreate` 只在用户点击“仍然新建”时进入，失败重试保持同一新 ID，
 *   直到输入变化或操作成功。
 */
export function newExtractOperation(
  rawText: string,
  courseId: string,
  requestId: string,
): ExtractOperationState {
  return {
    requestId,
    forceCreate: false,
    submitted: false,
    rawText: rawText.trim(),
    courseId: courseId.trim(),
  }
}

export function editExtractInput(
  operation: ExtractOperationState,
  rawText: string,
  courseId: string,
  nextRequestId: string,
): ExtractOperationState {
  const normalizedText = rawText.trim()
  const normalizedCourse = courseId.trim()
  if (normalizedText === operation.rawText && normalizedCourse === operation.courseId) {
    return operation
  }
  if (!operation.submitted) {
    return {
      ...operation,
      rawText: normalizedText,
      courseId: normalizedCourse,
    }
  }
  return {
    requestId: nextRequestId,
    forceCreate: false,
    submitted: false,
    rawText: normalizedText,
    courseId: normalizedCourse,
  }
}

export function markExtractSubmitted(
  operation: ExtractOperationState,
): ExtractOperationState {
  return { ...operation, submitted: true }
}

/** 响应返回时若当前操作身份已变化，旧响应必须丢弃。 */
export function isStaleExtractResponse(
  current: ExtractOperationState,
  submittedRequestId: string,
): boolean {
  return current.requestId !== submittedRequestId
}

export function forceCreateOperation(
  operation: ExtractOperationState,
  nextRequestId: string,
): ExtractOperationState {
  if (operation.forceCreate) {
    return operation
  }
  return {
    ...operation,
    requestId: nextRequestId,
    forceCreate: true,
    submitted: false,
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
    {
      label: '确认原因',
      before: textOrDash(task?.time_ambiguity_reason ?? null),
      after: textOrDash(draft.new_time_ambiguity_reason),
    },
    {
      // 截止时间必须与看板同一格式，包含用户时区标签。
      label: '截止时间（含时区）',
      before: task === null ? '—' : deadlineText(task),
      after: draftNewDeadline(draft),
    },
  ]
  return rows.map((row) => ({
    ...row,
    changed: row.before.trim() !== row.after.trim(),
  }))
}

/** 草案有效期提示；按草案时区展示，过期后后端返回 `DRAFT_EXPIRED_OR_NOT_FOUND`。 */
export function draftExpiryText(draft: TaskChangeDraft): string | null {
  const expires = formatTimestamp(draft.expires_at, draft.user_timezone)
  return expires === null ? null : `草案有效期至 ${expires}`
}

/**
 * 草案的新截止时间必须按草案携带的**用户时区快照**展示，而不是浏览器本地时区。
 *
 * 时区缺失时返回明确提示，不能静默回退 `Intl` 默认时区——否则同一份草案在不同电脑上
 * 会显示成不同时刻。`timezone` 参数仅用于草案缺少快照时复用目标任务的时区。
 */
export function draftNewDeadline(
  draft: TaskChangeDraft,
  timezone: string | null = null,
): string {
  return formatDueAt(draft.new_due_at, draft.user_timezone ?? timezone) ?? '未生成截止时间'
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
