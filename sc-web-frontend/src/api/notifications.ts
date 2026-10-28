import { apiRequest } from '@/api/client'

/**
 * 站内通知 API（`04` §4.7 `API-NOTIF-001/002`，TASK-802）。
 *
 * `notification_id` / `biz_id` 全程按十进制字符串处理；`read_status` 与 `outbound_status`
 * 保持后端枚举原样，前端不自行发明状态。
 */

export type NotificationBizType =
  | 'DDL_T24H'
  | 'DDL_T6H'
  | 'DDL_OVERDUE'
  | 'DDL_FOLLOW_UP'
  | 'DOC_INGEST_READY'
  | 'DOC_INGEST_FAILED'
  | 'COURSE_REBUILD_DONE'

export type NotificationReadStatus = 'UNREAD' | 'READ'

export type NotificationOutboundStatus =
  | 'PENDING_IM'
  | 'RATE_LIMITED_DELAYED'
  | 'SENT_IM'
  | 'FALLBACK_WEB_ONLY'

export type NotificationFilter = 'ALL' | 'UNREAD'

/** `04` §4.7.1 `NotificationVO`。 */
export interface NotificationItem {
  notification_id: string
  biz_type: NotificationBizType
  biz_id: string | null
  title: string
  content: string
  read_status: NotificationReadStatus
  outbound_status: NotificationOutboundStatus
  sent_im_at: string | null
  read_at: string | null
  created_at: string
}

/** `04` §4.7.1 `API-NOTIF-001` 响应。 */
export interface NotificationList {
  unread_count: number
  page: number
  size: number
  total: number
  total_pages: number
  items: NotificationItem[]
}

export interface NotificationListParams {
  readStatus?: NotificationReadStatus
  page?: number
  size?: number
}

export interface MarkNotificationsReadRequest {
  mark_all: boolean
  notification_ids?: string[] | null
}

/** `04` §4.7.2 `API-NOTIF-002` 响应。 */
export interface MarkReadResult {
  updated_count: number
  remaining_unread_count: number
}

/** `API-NOTIF-001`：分页查询站内通知与全局未读数。 */
export function listNotifications(
  params: NotificationListParams = {},
): Promise<NotificationList> {
  const query = new URLSearchParams()
  if (params.readStatus !== undefined) {
    query.set('read_status', params.readStatus)
  }
  if (params.page !== undefined) {
    query.set('page', String(params.page))
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size))
  }
  const suffix = query.toString() === '' ? '' : `?${query.toString()}`
  return apiRequest<NotificationList>(`/v1/notifications${suffix}`)
}

/** `API-NOTIF-002`：标记单条或全部未读通知为已读（后端幂等）。 */
export function markNotificationsRead(
  request: MarkNotificationsReadRequest,
): Promise<MarkReadResult> {
  return apiRequest<MarkReadResult>('/v1/notifications/read', {
    method: 'PATCH',
    body: request,
  })
}
