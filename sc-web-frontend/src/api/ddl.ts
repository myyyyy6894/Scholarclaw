import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/**
 * DDL 雷达与待办任务 API（`04` §4.6 `API-DDL-001~007`）。
 *
 * 约定：
 *
 * * 所有 `BIGINT` 业务 ID 一律按十进制字符串收发（`04` §2.6），不做数值转换；
 * * 字段名保持后端 `snake_case` 原样，避免中间层静默丢字段；
 * * `due_at` 缺失（`PENDING_TIME`）就是 `null`，前端不得补造时间。
 */

export type DdlTaskStatus =
  | 'PENDING_TIME'
  | 'PENDING_CONFIRM'
  | 'ACTIVE'
  | 'OVERDUE'
  | 'COMPLETED'
  | 'CANCELED'

export type DdlTimePrecision =
  | 'EXACT_DATETIME'
  | 'DATE_ONLY'
  | 'VAGUE_OR_MISSING'
  | 'INVALID_OR_PAST'

export type DdlStatusGroup = 'ALL' | 'PENDING_INBOX' | 'ACTIVE_BOARD' | 'COMPLETED'

export type DdlExtractOutcome =
  | 'CREATED'
  | 'IDEMPOTENT_HIT'
  | 'SEMANTIC_DUPLICATE_HINT'
  | 'CHANGE_DRAFT_CONFIRM_REQUIRED'

export type DraftDecision = 'CONFIRM_UPDATE' | 'CREATE_NEW' | 'DISCARD'

/** `04` §4.6.1 `DdlTaskVO`。 */
export interface DdlTask {
  task_id: BigIntId
  display_code: string
  course_id: BigIntId | null
  course_name: string
  task_title: string
  deliverable_format: string | null
  submission_channel: string | null
  raw_source_text: string | null
  raw_time_text: string | null
  time_precision: DdlTimePrecision
  is_relative_time: boolean
  due_date: string | null
  due_time: string | null
  due_at: string | null
  time_inferred: boolean
  user_timezone: string
  confidence: number | null
  status: DdlTaskStatus
  follow_up_at: string | null
  follow_up_count: number
  reminded_t24h: boolean
  reminded_t6h: boolean
  reminded_overdue: boolean
  source_platform: string
  completed_at: string | null
  created_at: string
  updated_at: string
}

/** `04` §4.6.1 `TaskChangeDraftVO`。 */
export interface TaskChangeDraft {
  target_task_id: BigIntId
  new_task_title: string
  new_time_precision: DdlTimePrecision
  new_due_date: string | null
  new_due_time: string | null
  new_due_at: string | null
  new_time_inferred: boolean
  new_deliverable_format: string | null
  new_submission_channel: string | null
  raw_source_text: string | null
  raw_time_text: string | null
  expires_at: string
}

/** `04` §4.6.3 `API-DDL-003` 响应。 */
export interface DdlTaskBoard {
  pending_inbox_tasks: DdlTask[]
  pending_change_drafts: TaskChangeDraft[]
  active_tasks: DdlTask[]
  completed_tasks: DdlTask[]
}

/** `04` §4.6.1 `API-DDL-001` 响应。 */
export interface DdlExtractResult {
  extract_outcome: DdlExtractOutcome
  task: DdlTask
  change_draft: TaskChangeDraft | null
}

export interface ExtractDdlTaskRequest {
  raw_text: string
  client_request_id: string
  force_create?: boolean
  course_id?: BigIntId | null
  reference_time?: string | null
}

export interface CreateDdlTaskRequest {
  client_request_id: string
  course_id?: BigIntId | null
  course_name: string
  task_title: string
  /** 缺省为 `EXACT_DATETIME`（`04` §4.6.2）。 */
  time_precision?: Exclude<DdlTimePrecision, 'INVALID_OR_PAST'>
  due_date?: string | null
  due_time?: string | null
  deliverable_format?: string | null
  submission_channel?: string | null
}

export interface UpdateDdlTaskRequest {
  course_id?: BigIntId | null
  course_name: string
  task_title: string
  time_precision: Exclude<DdlTimePrecision, 'INVALID_OR_PAST'>
  due_date?: string | null
  due_time?: string | null
  deliverable_format?: string | null
  submission_channel?: string | null
}

export interface DraftDecisionRequest {
  decision: DraftDecision
  client_request_id: string
}

export interface DeleteDdlTaskResult {
  deleted_task_id: BigIntId
  status: DdlTaskStatus
}

/** `API-DDL-003`：看板；`status_group` 缺省为 `ALL`（不含 `CANCELED`）。 */
export function listDdlTasks(params: {
  statusGroup?: DdlStatusGroup
  courseId?: BigIntId | null
} = {}): Promise<DdlTaskBoard> {
  const query = new URLSearchParams()
  if (params.statusGroup !== undefined) {
    query.set('status_group', params.statusGroup)
  }
  if (params.courseId !== undefined && params.courseId !== null && params.courseId !== '') {
    query.set('course_id', params.courseId)
  }
  const suffix = query.toString() === '' ? '' : `?${query.toString()}`
  return apiRequest<DdlTaskBoard>(`/v1/ddl/tasks${suffix}`)
}

/** `API-DDL-001`：粘贴通知文本提取并创建任务。 */
export function extractDdlTask(request: ExtractDdlTaskRequest): Promise<DdlExtractResult> {
  return apiRequest<DdlExtractResult>('/v1/ddl/tasks/extract', {
    method: 'POST',
    body: request,
  })
}

/** `API-DDL-002`：手动表单新建任务。 */
export function createDdlTask(request: CreateDdlTaskRequest): Promise<DdlTask> {
  return apiRequest<DdlTask>('/v1/ddl/tasks', { method: 'POST', body: request })
}

/** `API-DDL-004`：编辑任务或补充时间确认激活。 */
export function updateDdlTask(
  taskId: BigIntId,
  request: UpdateDdlTaskRequest,
): Promise<DdlTask> {
  return apiRequest<DdlTask>(`/v1/ddl/tasks/${encodeURIComponent(taskId)}`, {
    method: 'PUT',
    body: request,
  })
}

/** `API-DDL-005`：确认更新 / 仍然新建 / 忽略重复通知变更草案。 */
export function decideDdlDraft(
  taskId: BigIntId,
  request: DraftDecisionRequest,
): Promise<DdlTask> {
  return apiRequest<DdlTask>(
    `/v1/ddl/tasks/${encodeURIComponent(taskId)}/draft-decision`,
    { method: 'POST', body: request },
  )
}

/** `API-DDL-006`：标记完成 / 取消完成。 */
export function setDdlTaskCompletion(taskId: BigIntId, completed: boolean): Promise<DdlTask> {
  return apiRequest<DdlTask>(
    `/v1/ddl/tasks/${encodeURIComponent(taskId)}/completion`,
    { method: 'PATCH', body: { completed } },
  )
}

/** `API-DDL-007`：删除（置为 `CANCELED`）任务。 */
export function deleteDdlTask(taskId: BigIntId): Promise<DeleteDdlTaskResult> {
  return apiRequest<DeleteDdlTaskResult>(`/v1/ddl/tasks/${encodeURIComponent(taskId)}`, {
    method: 'DELETE',
  })
}
