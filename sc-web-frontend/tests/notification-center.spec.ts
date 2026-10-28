import { describe, expect, it } from 'vitest'

import type { NotificationItem } from '@/api/notifications'
import {
  bizTypeLabel,
  bizTypeTagType,
  canMarkAll,
  formatNotificationTime,
  mergeNotificationPage,
  notificationTarget,
  outboundStatusLabel,
  readStatusLabel,
  readStatusParam,
  unreadBadge,
} from '@/views/notifications/notification-center'

function item(id: string, overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    notification_id: id,
    biz_type: 'DDL_T24H',
    biz_id: '102',
    title: '标题',
    content: '内容',
    read_status: 'UNREAD',
    outbound_status: 'FALLBACK_WEB_ONLY',
    sent_im_at: null,
    read_at: null,
    created_at: '2026-10-28T10:00:00+08:00',
    ...overrides,
  }
}

describe('notification center helpers', () => {
  it('七类通知都有中文标签与稳定标签色', () => {
    expect(bizTypeLabel('DDL_T24H')).toBe('T-24h 提醒')
    expect(bizTypeLabel('DDL_T6H')).toBe('T-6h 紧急')
    expect(bizTypeLabel('DDL_OVERDUE')).toBe('逾期提醒')
    expect(bizTypeLabel('DDL_FOLLOW_UP')).toBe('待确认跟进')
    expect(bizTypeLabel('DOC_INGEST_READY')).toBe('课件入库')
    expect(bizTypeLabel('DOC_INGEST_FAILED')).toBe('入库失败')
    expect(bizTypeLabel('COURSE_REBUILD_DONE')).toBe('重建完成')
    expect(bizTypeTagType('DDL_T6H')).toBe('danger')
    expect(bizTypeTagType('DOC_INGEST_READY')).toBe('success')
  })

  it('出站状态与阅读状态使用契约取值', () => {
    expect(outboundStatusLabel('FALLBACK_WEB_ONLY')).toBe('仅站内信')
    expect(outboundStatusLabel('RATE_LIMITED_DELAYED')).toBe('免打扰顺延')
    expect(readStatusLabel('UNREAD')).toBe('未读')
    expect(readStatusLabel('READ')).toBe('已读')
  })

  it('时间展示直接使用后端偏移量墙钟，不按浏览器时区换算', () => {
    expect(formatNotificationTime('2026-10-28T10:31:24.510+08:00'))
      .toBe('2026-10-28 10:31')
    expect(formatNotificationTime('2026-10-28T23:59:00+00:00'))
      .toBe('2026-10-28 23:59')
    expect(formatNotificationTime(null)).toBe('')
  })

  it('角标超过 99 显示 99+，0 隐藏', () => {
    expect(unreadBadge(0)).toBe('')
    expect(unreadBadge(3)).toBe('3')
    expect(unreadBadge(99)).toBe('99')
    expect(unreadBadge(100)).toBe('99+')
    expect(canMarkAll(0)).toBe(false)
    expect(canMarkAll(1)).toBe(true)
  })

  it('过滤切换映射为 read_status 参数', () => {
    expect(readStatusParam('UNREAD')).toBe('UNREAD')
    expect(readStatusParam('ALL')).toBeUndefined()
  })

  it('通知点击跳转到对应页面', () => {
    expect(notificationTarget('DDL_T24H')).toBe('ddl')
    expect(notificationTarget('DOC_INGEST_READY')).toBe('knowledge')
    expect(notificationTarget('DOC_INGEST_FAILED')).toBe('knowledge')
    expect(notificationTarget('COURSE_REBUILD_DONE')).toBe('courses')
  })

  it('分页合并按 ID 去重且保持顺序', () => {
    const first = [item('1'), item('2')]
    const second = [item('2'), item('3')]

    expect(mergeNotificationPage(first, second, false).map((one) => one.notification_id))
      .toEqual(['1', '2', '3'])
    expect(mergeNotificationPage(first, second, true).map((one) => one.notification_id))
      .toEqual(['2', '3'])
  })
})
