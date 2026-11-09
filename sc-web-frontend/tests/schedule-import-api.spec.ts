import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetApiClient } from '@/api/client'
import { confirmScheduleImport, createTextScheduleImport, getScheduleImport } from '@/api/schedule-import'

function envelope(): Response {
  return new Response(JSON.stringify({ code: 'OK', message: 'ok', data: {}, trace_id: 'tr_test', timestamp: '2026-01-01T00:00:00+08:00' }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

describe('课表导入 API', () => {
  afterEach(() => { vi.unstubAllGlobals(); resetApiClient() })
  it('保持字符串 ID 和确认幂等标识，不自动保存建议', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(envelope()))
    vi.stubGlobal('fetch', fetchMock)
    await createTextScheduleImport('9007199254740993', '高数 周一 1-2节 1-16周', 'create-1')
    const createPayload = (fetchMock.mock.calls[0]?.[1] as RequestInit).body
    const createBody = JSON.parse(typeof createPayload === 'string' ? createPayload : '{}') as Record<string, unknown>
    expect(createBody.term_id).toBe('9007199254740993')
    expect(createBody.client_request_id).toBe('create-1')
    await getScheduleImport('9007199254740994')
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('/9007199254740994')
    await confirmScheduleImport('9007199254740994', { version: 3, client_request_id: 'confirm-1', rules: [] })
    const confirmPayload = (fetchMock.mock.calls[2]?.[1] as RequestInit).body
    const confirmBody = JSON.parse(typeof confirmPayload === 'string' ? confirmPayload : '{}') as Record<string, unknown>
    expect(confirmBody.version).toBe(3)
    expect(confirmBody.client_request_id).toBe('confirm-1')
  })
})
