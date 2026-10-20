import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

import { resetApiClient } from '@/api/client'
import {
  clearApiKeys,
  getUserConfig,
  probeModel,
  saveModelConfig,
  updatePreferences,
} from '@/api/user-config'

/**
 * 用户配置 API 模块契约（`04` §4.2 `API-USER-001~005`）。
 *
 * 重点：路径/方法/查询参数与文档一致；明文 Key 只出现在请求体中，绝不进入 URL。
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

const CONFIG = {
  user_id: '10001',
  username: 'zhangsan_cs',
  chat_provider: 'DEEPSEEK',
  chat_base_url: 'https://api.deepseek.com/v1',
  has_chat_api_key: true,
  chat_api_key_masked: 'sk-****8a9f',
  chat_model: 'deepseek-chat',
  default_embedding_provider: 'OPENAI_COMPATIBLE',
  embedding_base_url: 'https://api.siliconflow.cn/v1',
  has_embedding_api_key: true,
  embedding_api_key_masked: 'sk-****3c2d',
  default_embedding_model: 'BAAI/bge-m3',
  default_embedding_dim: 1024,
  timezone: 'Asia/Shanghai',
  dnd_start_time: '23:30:00',
  dnd_end_time: '07:30:00',
  bound_im_platform: null,
  bound_im_uid: null,
  updated_at: '2026-10-20T00:00:00.000+08:00',
}

describe('user config api', () => {
  beforeEach(() => {
    resetApiClient()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('GET /v1/user/config 返回掩码视图', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(CONFIG)))
    vi.stubGlobal('fetch', fetchMock)

    const config = await getUserConfig()

    expect(captured(fetchMock).url).toBe('/api/v1/user/config')
    expect(captured(fetchMock).method).toBe('GET')
    expect(config.chat_api_key_masked).toBe('sk-****8a9f')
  })

  it('探测使用 POST，且明文 Key 只在请求体、不在 URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(envelope({
        reachable: true,
        probe_type: 'EMBEDDING',
        model_name: 'BAAI/bge-m3',
        actual_dim: 1024,
        latency_ms: 42,
      })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await probeModel({
      probe_type: 'EMBEDDING',
      provider: 'OPENAI_COMPATIBLE',
      base_url: 'https://api.siliconflow.cn/v1',
      api_key: 'sk-secret-value',
      use_saved_key: false,
      model_name: 'BAAI/bge-m3',
    })

    const request = captured(fetchMock)
    expect(request.url).toBe('/api/v1/user/model-config/probe')
    expect(request.url).not.toContain('sk-secret-value')
    expect(request.method).toBe('POST')
    expect(request.body).toContain('"api_key":"sk-secret-value"')
    expect(result.actual_dim).toBe(1024)
  })

  it('保存使用 PUT /v1/user/model-config，返回更新后的配置', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(envelope({ ...CONFIG, existing_course_count: 0 })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const updated = await saveModelConfig({
      chat_provider: 'DEEPSEEK',
      chat_base_url: 'https://api.deepseek.com/v1',
      chat_model: 'deepseek-chat',
      default_embedding_provider: 'OPENAI_COMPATIBLE',
      embedding_base_url: 'https://api.siliconflow.cn/v1',
      reuse_chat_api_key: true,
      default_embedding_model: 'BAAI/bge-m3',
    })

    expect(captured(fetchMock).url).toBe('/api/v1/user/model-config')
    expect(captured(fetchMock).method).toBe('PUT')
    expect(updated.existing_course_count).toBe(0)
  })

  it('偏好更新使用 PUT /v1/user/preferences', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(envelope(CONFIG)))
    vi.stubGlobal('fetch', fetchMock)

    await updatePreferences({
      timezone: 'Asia/Shanghai',
      dnd_start_time: '23:30:00',
      dnd_end_time: '07:30:00',
      bound_im_platform: null,
      bound_im_uid: null,
    })

    expect(captured(fetchMock).url).toBe('/api/v1/user/preferences')
    expect(captured(fetchMock).method).toBe('PUT')
  })

  it('销毁 Key 使用 DELETE，target 在查询参数中且默认 ALL', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(envelope(CONFIG))))
    vi.stubGlobal('fetch', fetchMock)

    await clearApiKeys()
    await clearApiKeys('CHAT')
    await clearApiKeys('EMBEDDING')

    expect(captured(fetchMock, 0).url).toBe('/api/v1/user/model-config/keys?target=ALL')
    expect(captured(fetchMock, 1).url).toBe('/api/v1/user/model-config/keys?target=CHAT')
    expect(captured(fetchMock, 2).url).toBe('/api/v1/user/model-config/keys?target=EMBEDDING')
    expect(captured(fetchMock, 0).method).toBe('DELETE')
  })
})
