import type {
  NotificationBizType,
  NotificationFilter,
  NotificationItem,
  NotificationOutboundStatus,
  NotificationReadStatus,
} from '@/api/notifications'

/**
 * 通知中心的纯展示/合并逻辑（TASK-802）。
 *
 * 时间沿用 P7 的展示铁律：直接取后端 ISO-8601 中的偏移量墙钟，不按浏览器时区二次换算，
 * 避免同一通知在不同电脑上显示不同时间。组件只负责渲染这些结果。
 */

export const BIZ_TYPE_LABELS: Record<NotificationBizType, string> = {
  DDL_T24H: 'T-24h 提醒',
  DDL_T6H: 'T-6h 紧急',
  DDL_OVERDUE: '逾期提醒',
  DDL_FOLLOW_UP: '待确认跟进',
  DOC_INGEST_READY: '课件入库',
  DOC_INGEST_FAILED: '入库失败',
  COURSE_REBUILD_DONE: '重建完成',
}

export type NotificationTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const BIZ_TYPE_TAG_TYPES: Record<NotificationBizType, NotificationTagType> = {
  DDL_T24H: 'warning',
  DDL_T6H: 'danger',
  DDL_OVERDUE: 'danger',
  DDL_FOLLOW_UP: 'info',
  DOC_INGEST_READY: 'success',
  DOC_INGEST_FAILED: 'danger',
  COURSE_REBUILD_DONE: 'success',
}

export const OUTBOUND_STATUS_LABELS: Record<NotificationOutboundStatus, string> = {
  PENDING_IM: '待推送 IM',
  RATE_LIMITED_DELAYED: '免打扰顺延',
  SENT_IM: '已推送',
  FALLBACK_WEB_ONLY: '仅站内信',
}

export function bizTypeLabel(type: NotificationBizType): string {
  return BIZ_TYPE_LABELS[type] ?? type
}

export function bizTypeTagType(type: NotificationBizType): NotificationTagType {
  return BIZ_TYPE_TAG_TYPES[type] ?? 'info'
}

export function outboundStatusLabel(status: NotificationOutboundStatus): string {
  return OUTBOUND_STATUS_LABELS[status] ?? status
}

export function readStatusLabel(status: NotificationReadStatus): string {
  return status === 'READ' ? '已读' : '未读'
}

/** 通知对应的页面入口；只做页面级跳转，不伪造不存在的深链参数。 */
export function notificationTarget(type: NotificationBizType): 'ddl' | 'knowledge' | 'courses' {
  if (type.startsWith('DDL_')) {
    return 'ddl'
  }
  if (type === 'COURSE_REBUILD_DONE') {
    return 'courses'
  }
  return 'knowledge'
}

/** 后端 ISO-8601 -> `YYYY-MM-DD HH:mm`，按后端偏移量的墙钟展示。 */
export function formatNotificationTime(value: string | null): string {
  if (!value) {
    return ''
  }
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(value)
  return match ? `${match[1]} ${match[2]}` : value
}

/** 角标文案：超过 99 显示 `99+`，避免导航栏被长数字撑开。 */
export function unreadBadge(count: number): string {
  if (!Number.isFinite(count) || count <= 0) {
    return ''
  }
  return count > 99 ? '99+' : String(Math.trunc(count))
}

export function canMarkAll(unreadCount: number): boolean {
  return Number.isFinite(unreadCount) && unreadCount > 0
}

/** 过滤切换后向后端传的 read_status；`ALL` 不传。 */
export function readStatusParam(filter: NotificationFilter): NotificationReadStatus | undefined {
  return filter === 'UNREAD' ? 'UNREAD' : undefined
}

/**
 * 合并分页结果：按 `notification_id` 去重，保持后端返回顺序。
 *
 * 轮询/标记已读后重新拉取第一页时，用 `reset=true` 替换；加载更多时追加。
 */
export function mergeNotificationPage(
  current: NotificationItem[],
  next: NotificationItem[],
  reset: boolean,
): NotificationItem[] {
  const base = reset ? [] : current
  const seen = new Set(base.map((item) => item.notification_id))
  const merged = [...base]
  for (const item of next) {
    if (!seen.has(item.notification_id)) {
      merged.push(item)
      seen.add(item.notification_id)
    }
  }
  return merged
}
