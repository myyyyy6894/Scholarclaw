import { afterEach, describe, expect, it, vi } from 'vitest'
import { configureApiClient, resetApiClient } from '@/api/client'
import { CLIENT_ERROR_CODES } from '@/api/error'
import {
  confirmScheduleImport, createImageScheduleImport, createTextScheduleImport, getScheduleImport,
  getScheduleImportImage, retryImageScheduleImport,
} from '@/api/schedule-import'

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
  it('图片上传和重试传递明确确认，预览使用鉴权头且不缓存', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(envelope()))
    vi.stubGlobal('fetch', fetchMock)
    const image = new File(['synthetic'], 'schedule.png', { type: 'image/png' })
    await createImageScheduleImport('9007199254740993', image, 'image-1', 'consent-1')
    const upload = (fetchMock.mock.calls[0]?.[1] as RequestInit).body as FormData
    expect(upload.get('term_id')).toBe('9007199254740993')
    expect(upload.get('image')).toBe(image)
    expect(upload.get('confirm_external_transfer')).toBe('true')
    expect(upload.get('consent_token')).toBe('consent-1')
    await retryImageScheduleImport('9007199254740994', 3, 'consent-2')
    const payload = (fetchMock.mock.calls[1]?.[1] as RequestInit).body
    expect(JSON.parse(typeof payload === 'string' ? payload : '{}')).toEqual({ version: 3, consent_token: 'consent-2', confirm_external_transfer: true })
    configureApiClient({ tokenProvider: () => 'synthetic-token' })
    fetchMock.mockResolvedValueOnce(new Response('image', { headers: { 'Content-Type': 'image/png;charset=UTF-8' } }))
    const preview = await getScheduleImportImage('9007199254740994')
    expect(preview.type).toBe('image/png')
    const request = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(request.cache).toBe('no-store')
    expect((request.headers as Record<string, string>).Authorization).toBe('Bearer synthetic-token')
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/v1/schedule/imports/9007199254740994/image')
  })
  it('拒绝非图片和没有 Content-Length 时超限的预览', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('not an image', { headers: { 'Content-Type': 'image/svg+xml' } }))
      .mockResolvedValueOnce(new Response(new Uint8Array(10 * 1024 * 1024 + 1), { headers: { 'Content-Type': 'image/png' } })))
    await expect(getScheduleImportImage('1')).rejects.toMatchObject({ code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE })
    await expect(getScheduleImportImage('1')).rejects.toMatchObject({ code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE })
  })
})
