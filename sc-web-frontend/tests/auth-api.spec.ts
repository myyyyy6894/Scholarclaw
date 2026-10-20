import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

import { login, logout, register, toAuthSession } from '@/api/auth'
import { resetApiClient } from '@/api/client'

/**
 * 认证 API 模块契约（`04` §4.1 `API-AUTH-001/002/003`）。
 *
 * 重点：请求路径/方法/字段与文档一致；会话只保留 token 与展示信息，不整包落盘。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope<T>(data: T): Record<string, unknown> {
  return {
    code: 'OK',
    message: '操作成功',
    data,
    trace_id: 'tr_0123456789ab',
    timestamp: '2026-10-20T00:00:00.000+08:00',
  }
}

function captured(fetchMock: Mock, index = 0): { url: string; method?: string; body?: string } {
  const init = fetchMock.mock.calls[index]?.[1] as RequestInit | undefined
  return {
    url: String(fetchMock.mock.calls[index]?.[0]),
    method: init?.method,
    body: typeof init?.body === 'string' ? init.body : undefined,
  }
}

const AUTH_RESPONSE = {
  user_id: '10001',
  username: 'zhangsan_cs',
  account_status: 'ACTIVE',
  access_token: 'jwt-token-value',
  token_type: 'Bearer',
  expires_in: 604800,
  has_byok_config: false,
  created_at: '2026-10-20T00:00:00.000+08:00',
}

describe('auth api', () => {
  beforeEach(() => {
    resetApiClient()
    // 未注入 token：登录/注册接口本身不需要 Authorization
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('登录使用 POST /v1/auth/login', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(AUTH_RESPONSE)))
    vi.stubGlobal('fetch', fetchMock)

    const response = await login({ username: 'zhangsan_cs', password: 'Passw0rd!' })

    const request = captured(fetchMock)
    expect(request.url).toBe('/api/v1/auth/login')
    expect(request.method).toBe('POST')
    expect(request.body).toBe(JSON.stringify({ username: 'zhangsan_cs', password: 'Passw0rd!' }))
    expect(response.user_id).toBe('10001')
  })

  it('注册使用 POST /v1/auth/register，并原样回传 user_id 字符串', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(AUTH_RESPONSE), 201))
    vi.stubGlobal('fetch', fetchMock)

    const response = await register({ username: 'zhangsan_cs', password: 'Passw0rd!' })

    const request = captured(fetchMock)
    expect(request.url).toBe('/api/v1/auth/register')
    expect(request.method).toBe('POST')
    expect(typeof response.user_id).toBe('string')
  })

  it('登出使用 POST /v1/auth/logout 且不返回数据', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(null)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(logout()).resolves.toBeUndefined()

    expect(captured(fetchMock).url).toBe('/api/v1/auth/logout')
    expect(captured(fetchMock).method).toBe('POST')
  })

  it('toAuthSession 只保留 token 与展示信息，不整包保存响应', () => {
    const session = toAuthSession(AUTH_RESPONSE)

    expect(session).toEqual({
      token: 'jwt-token-value',
      user: { user_id: '10001', username: 'zhangsan_cs' },
    })
    expect(Object.keys(session.user)).toEqual(['user_id', 'username'])
    expect(JSON.stringify(session)).not.toContain('account_status')
    expect(JSON.stringify(session)).not.toContain('expires_in')
  })
})
