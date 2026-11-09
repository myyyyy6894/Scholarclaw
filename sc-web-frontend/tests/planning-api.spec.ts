import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetApiClient } from '@/api/client'
import { acceptStudyPlan, createStudyPlan, deletePersonalEvent, savePlanningPreference, saveTaskProfile } from '@/api/planning'

function response(): Response {
  return new Response(JSON.stringify({ code: 'OK', message: 'ok', data: {}, trace_id: 'test', timestamp: '2026-01-01T00:00:00Z' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('时间规划输入 API', () => {
  afterEach(() => { vi.unstubAllGlobals(); resetApiClient() })

  it('保留大整数的字符串任务 ID、版本和独立工时，不提交截止日期', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response()))
    vi.stubGlobal('fetch', fetchMock)
    await saveTaskProfile('9007199254740993', {
      estimated_minutes: 120, remaining_minutes: 90, priority: 2,
      earliest_start_at: '2026-11-09T09:00:00Z', splittable: true, min_block_minutes: 30, version: 7,
    })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/planning/tasks/9007199254740993')
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(request.method).toBe('PUT')
    expect(JSON.parse(typeof request.body === 'string' ? request.body : '{}')).toEqual({ estimated_minutes: 120, remaining_minutes: 90, priority: 2,
      earliest_start_at: '2026-11-09T09:00:00Z', splittable: true, min_block_minutes: 30, version: 7 })
  })

  it('显式发送本地时区与时段，并带版本删除固定日程', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response()))
    vi.stubGlobal('fetch', fetchMock)
    await savePlanningPreference({ timezone: 'Asia/Shanghai', windows: [{ weekday: 1, start_minute: 1080, end_minute: 1440 }], focus_minutes: 50, daily_limit_minutes: 180, buffer_minutes: 10, version: 4 })
    const preferenceBody = (fetchMock.mock.calls[0]?.[1] as RequestInit).body
    expect(JSON.parse(typeof preferenceBody === 'string' ? preferenceBody : '{}')).toMatchObject({ timezone: 'Asia/Shanghai', version: 4, windows: [{ weekday: 1, start_minute: 1080, end_minute: 1440 }] })
    await deletePersonalEvent('9007199254740994', 5)
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/planning/events/9007199254740994?version=5')
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit).method).toBe('DELETE')
  })

  it('生成及部分采纳携带稳定请求标识、版本和字符串时间块 ID', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response()))
    vi.stubGlobal('fetch', fetchMock)
    await createStudyPlan({ from_date: '2026-11-10', to_date: '2026-11-16', client_request_id: 'generate-1', confirm_no_active_term: true })
    const createBody = (fetchMock.mock.calls[0]?.[1] as RequestInit).body
    expect(JSON.parse(typeof createBody === 'string' ? createBody : '{}')).toMatchObject({ client_request_id: 'generate-1', confirm_no_active_term: true })
    await acceptStudyPlan('9007199254740993', 7, 'accept-1', ['9007199254740994'])
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/planning/plans/9007199254740993/accept')
    const acceptBody = (fetchMock.mock.calls[1]?.[1] as RequestInit).body
    expect(JSON.parse(typeof acceptBody === 'string' ? acceptBody : '{}')).toEqual({ revision: 7, client_request_id: 'accept-1', block_ids: ['9007199254740994'] })
  })
})
