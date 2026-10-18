import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

import { apiRequest, configureApiClient, resetApiClient, resolveApiBaseUrl } from '@/api/client'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/error'
import { SUCCESS_CODE } from '@/api/types'

/**
 * API client 契约测试（`04` §2.3 / §2.6 / §2.7.1）。
 *
 * 覆盖四类必须在骨架阶段就锁死的行为：信封解包、错误码原样透传、
 * `Authorization` 头注入、以及 `BIGINT` ID 保持字符串不被转成 Number。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope<T>(data: T, overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    code: SUCCESS_CODE,
    message: '操作成功',
    data,
    trace_id: 'tr_0123456789ab',
    timestamp: '2026-10-18T01:00:00.000+08:00',
    ...overrides,
  }
}

interface CapturedRequest {
  method?: string
  headers: Record<string, string>
  body?: unknown
}

/**
 * 取出第 `index` 次 `fetch` 调用的请求参数。
 *
 * `vi.fn()` 的 `mock.calls` 是 `any[]`，直接取属性会触发 `no-unsafe-member-access`；
 * 这里把类型转换收口到一处，避免用例里散落 `as any`。
 */
function capturedRequest(fetchMock: Mock, index: number): CapturedRequest {
  const init = fetchMock.mock.calls[index]?.[1] as RequestInit | undefined
  return {
    method: init?.method,
    headers: (init?.headers ?? {}) as Record<string, string>,
    body: init?.body,
  }
}

describe('apiRequest', () => {
  beforeEach(() => {
    resetApiClient()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('成功时解包 data 并保持 ID 为字符串（04 §2.6）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(envelope({ course_id: '2001', course_name: '数据库系统概念' })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const data = await apiRequest<{ course_id: string; course_name: string }>('/v1/courses')

    expect(data.course_id).toBe('2001')
    expect(typeof data.course_id).toBe('string')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('请求路径基于 VITE_API_BASE_URL 拼接，默认 /api', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(null)))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/v1/courses')

    expect(resolveApiBaseUrl()).toBe('/api')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/courses')
  })

  it('业务失败时抛出携带服务端错误码与追踪号的 ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          envelope(null, { code: 'E-SYS-02', message: '该自定义 Base URL 未通过安全校验。' }),
          400,
        ),
      ),
    )

    await expect(apiRequest('/v1/user/config')).rejects.toMatchObject({
      code: 'E-SYS-02',
      traceId: 'tr_0123456789ab',
      httpStatus: 400,
    })
  })

  it('响应不是合法信封时抛 CLIENT_MALFORMED_RESPONSE（例如 Nginx 返回 HTML 错误页）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('<html>502 Bad Gateway</html>', { status: 502 })),
    )

    await expect(apiRequest('/v1/courses')).rejects.toMatchObject({
      code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE,
      httpStatus: 502,
    })
  })

  it('网络不可达时抛 CLIENT_NETWORK_ERROR 而不是原始异常', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await apiRequest('/v1/courses').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(CLIENT_ERROR_CODES.NETWORK)
    expect((error as ApiError).isClientSide).toBe(true)
  })

  it('已登录时附加 Authorization 头，未登录时不附加', async () => {
    // 注意：必须每次返回**新的** Response 对象。若用 mockResolvedValue 复用同一个 Response，
    // 第二次调用会因响应体已被读取而抛 "Body is unusable"，掩盖真正要断言的头部注入逻辑。
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(envelope(null))))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/v1/courses')
    expect(capturedRequest(fetchMock, 0).headers.Authorization).toBeUndefined()

    configureApiClient({ tokenProvider: () => 'jwt-token-value' })
    await apiRequest('/v1/courses')
    expect(capturedRequest(fetchMock, 1).headers.Authorization).toBe('Bearer jwt-token-value')
  })

  it('401 触发 unauthorizedHandler，并且仍然抛出可读错误', async () => {
    const onUnauthorized = vi.fn()
    configureApiClient({ unauthorizedHandler: onUnauthorized })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(envelope(null, { code: 'AUTH_TOKEN_REVOKED', message: '当前登录凭证已注销。' }), 401),
      ),
    )

    await expect(apiRequest('/v1/courses')).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('POST 请求带 JSON 请求体与 Content-Type', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(null)))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/v1/ddl/tasks', { method: 'POST', body: { course_id: '2001' } })

    const request = capturedRequest(fetchMock, 0)
    expect(request.method).toBe('POST')
    expect(request.headers['Content-Type']).toBe('application/json')
    expect(request.body).toBe(JSON.stringify({ course_id: '2001' }))
  })
})
