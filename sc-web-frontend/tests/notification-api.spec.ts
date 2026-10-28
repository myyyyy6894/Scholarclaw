import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { configureApiClient, resetApiClient } from '@/api/client'
import { listNotifications, markNotificationsRead } from '@/api/notifications'

function envelope<T>(data: T) {
  return {
    code: 'OK',
    message: 'OK',
    data,
    trace_id: 'tr_notiftest01',
    timestamp: '2026-10-28T12:00:00+08:00',
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function lastCall(fetchMock: ReturnType<typeof vi.fn>) {
  const call = fetchMock.mock.calls.at(-1)
  if (call === undefined) {
    throw new Error('fetch 未被调用')
  }
  const init = (call[1] ?? {}) as RequestInit
  return {
    url: String(call[0]),
    init,
    body: () => {
      if (typeof init.body !== 'string') {
        throw new Error('请求体不是 JSON 字符串')
      }
      return JSON.parse(init.body) as unknown
    },
  }
}

describe('notifications api', () => {
  beforeEach(() => {
    resetApiClient()
    configureApiClient({ tokenProvider: () => 'jwt-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('列表查询带鉴权、snake_case query 与字符串 ID', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        jsonResponse(
          envelope({
            unread_count: 1,
            page: 2,
            size: 10,
            total: 11,
            total_pages: 2,
            items: [
              {
                notification_id: '70001',
                biz_type: 'DDL_T24H',
                biz_id: '102',
                title: '标题',
                content: '内容',
                read_status: 'UNREAD',
                outbound_status: 'FALLBACK_WEB_ONLY',
                sent_im_at: null,
                read_at: null,
                created_at: '2026-10-28T10:00:00+08:00',
              },
            ],
          }),
        ),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await listNotifications({ readStatus: 'UNREAD', page: 2, size: 10 })

    const call = lastCall(fetchMock)
    expect(call.url).toContain('/api/v1/notifications?')
    expect(call.url).toContain('read_status=UNREAD')
    expect(call.url).toContain('page=2')
    expect(call.url).toContain('size=10')
    expect(call.init.method).toBe('GET')
    expect((call.init.headers as Record<string, string>).Authorization).toBe('Bearer jwt-token')
    expect(result.unread_count).toBe(1)
    expect(result.items[0]?.notification_id).toBe('70001')
    expect(result.items[0]?.biz_id).toBe('102')
  })

  it('无参数查询不追加问号', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        jsonResponse(envelope({ unread_count: 0, page: 1, size: 20, total: 0, total_pages: 0, items: [] })),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await listNotifications()

    expect(lastCall(fetchMock).url).toContain('/api/v1/notifications')
    expect(lastCall(fetchMock).url).not.toContain('?')
  })

  it('标记已读使用 PATCH 与 snake_case 请求体，ID 保持字符串', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(jsonResponse(envelope({ updated_count: 1, remaining_unread_count: 0 }))),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await markNotificationsRead({
      mark_all: false,
      notification_ids: ['70001'],
    })

    const call = lastCall(fetchMock)
    expect(call.url).toContain('/api/v1/notifications/read')
    expect(call.init.method).toBe('PATCH')
    expect(call.body()).toEqual({ mark_all: false, notification_ids: ['70001'] })
    expect(result.updated_count).toBe(1)
    expect(result.remaining_unread_count).toBe(0)
  })
})
