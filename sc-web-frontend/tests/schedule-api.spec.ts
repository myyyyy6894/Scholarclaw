import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetApiClient } from '@/api/client'
import { createScheduleRule, listOccurrences, saveScheduleException } from '@/api/schedule'

function envelope(data: unknown): Response {
  return new Response(JSON.stringify({ code: 'OK', message: 'ok', data, trace_id: 'tr_test', timestamp: '2026-01-01T00:00:00+08:00' }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

describe('课表 API', () => {
  afterEach(() => { vi.unstubAllGlobals(); resetApiClient() })

  it('查询保留大整数 ID 字符串和日期范围', async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope({ term_id: '9007199254740993', timezone: 'Asia/Shanghai', occurrences: [] }))
    vi.stubGlobal('fetch', fetchMock)
    await listOccurrences('9007199254740993', '2026-12-28', '2027-01-03')
    const url = String(fetchMock.mock.calls[0]?.[0])
    expect(url).toContain('term_id=9007199254740993')
    expect(url).toContain('from=2026-12-28')
    expect(url).toContain('to=2027-01-03')
  })

  it('保存课程和调课时显式携带确认与版本', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(envelope({})))
    vi.stubGlobal('fetch', fetchMock)
    await createScheduleRule({ term_id: '9007199254740993', course_id: null, title: '高数', teacher: null, room: null,
      weekday: 1, start_time: '08:00:00', end_time: '09:00:00', start_period: null, end_period: null,
      weeks: [1, 3], confirm_overlap: true })
    const firstBody = (fetchMock.mock.calls[0]?.[1] as RequestInit).body
    expect(typeof firstBody).toBe('string')
    const rule = JSON.parse(typeof firstBody === 'string' ? firstBody : '{}') as Record<string, unknown>
    expect(rule.term_id).toBe('9007199254740993')
    expect(rule.weeks).toEqual([1, 3])
    expect(rule.confirm_overlap).toBe(true)
    await saveScheduleException('9007199254740994', '2026-12-28', { kind: 'CANCEL', target_date: null,
      start_time: null, end_time: null, room: null, version: 3, confirm_overlap: false })
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('/9007199254740994/exceptions/2026-12-28')
    const secondBody = (fetchMock.mock.calls[1]?.[1] as RequestInit).body
    expect(typeof secondBody).toBe('string')
    const change = JSON.parse(typeof secondBody === 'string' ? secondBody : '{}') as Record<string, unknown>
    expect(change.version).toBe(3)
  })
})
