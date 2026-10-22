import { ApiError, CLIENT_ERROR_CODES } from '@/api/error'
import type { ApiResult } from '@/api/types'
import { SUCCESS_CODE } from '@/api/types'

/**
 * 统一 API client（`04` §2.3 / §2.7.1）。
 *
 * 设计要点：
 *
 * 1. **只用原生 `fetch`，不引入 axios。** 后端契约要求所有业务请求带 `Authorization: Bearer`，
 *    而 SSE 场景（`API-RAG-001`）同样需要带该头——浏览器的 `EventSource` **不支持自定义请求头**，
 *    后续必须用 `fetch` + `ReadableStream` 消费流。既然终究要自己处理流，再引入一层 HTTP 库
 *    只会多一套错误语义，故不引入。
 * 2. **不在这里做 UI 提示**：client 只负责协议与错误分类，是否弹窗由调用方决定，
 *    避免基础设施层依赖组件库。
 * 3. **与鉴权 store 解耦**：通过 `configureApiClient` 注入取令牌与 401 处理回调，
 *    避免 `client <-> store` 循环依赖。
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface ApiClientHooks {
  /** 返回当前登录令牌；返回 `null` 表示未登录，不附加 `Authorization` 头。 */
  tokenProvider?: () => string | null
  /** 收到 401 时的处理（清理本地会话、提示重新登录）。 */
  unauthorizedHandler?: () => void
}

export interface ApiRequestOptions {
  method?: HttpMethod
  /** 请求体；会被 JSON 序列化。注意其中的 ID 字段必须是字符串（`04` §2.6）。 */
  body?: unknown
  /** multipart 上传体；设置后不会再设置 `Content-Type`（由浏览器生成 boundary）。 */
  formData?: FormData
  signal?: AbortSignal
  extraHeaders?: Record<string, string>
}

let tokenProvider: () => string | null = () => null
let unauthorizedHandler: () => void = () => undefined

export function configureApiClient(hooks: ApiClientHooks): void {
  if (hooks.tokenProvider) {
    tokenProvider = hooks.tokenProvider
  }
  if (hooks.unauthorizedHandler) {
    unauthorizedHandler = hooks.unauthorizedHandler
  }
}

/** 恢复为未注入状态；供测试隔离使用。 */
export function resetApiClient(): void {
  tokenProvider = () => null
  unauthorizedHandler = () => undefined
}

/** 业务接口前缀；开发态走 Vite 代理，生产态走 Nginx 反向代理。 */
export function resolveApiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL
  if (!configured || configured.trim() === '') {
    return '/api'
  }
  return configured.trim().replace(/\/+$/, '')
}

/**
 * 发起请求并解包统一信封。
 *
 * @returns 信封中的 `data`。调用方按契约声明 `T`；若该接口允许无数据，请把 `T` 声明为可空类型。
 * @throws ApiError 网络不可达、响应非信封、或信封中 `code !== "OK"`。
 */
export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(options.extraHeaders ?? {}),
  }

  const token = tokenProvider()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let payload: string | FormData | undefined
  if (options.formData !== undefined) {
    payload = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path), {
      method,
      headers,
      body: payload,
      signal: options.signal ?? null,
    })
  } catch (error) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.NETWORK,
      message: '无法连接到服务端，请检查网络后重试。',
      cause: error,
    })
  }

  // 401 一律先清理本地会话：无论响应体是否可解析，登录态都已经失效
  if (response.status === 401) {
    unauthorizedHandler()
  }

  const envelope = await parseEnvelope<T>(response)

  if (envelope.code !== SUCCESS_CODE) {
    throw new ApiError({
      code: envelope.code,
      message: envelope.message,
      traceId: envelope.trace_id,
      httpStatus: response.status,
    })
  }

  return envelope.data as T
}

function buildUrl(path: string): string {
  return `${resolveApiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`
}

async function parseEnvelope<T>(response: Response): Promise<ApiResult<T>> {
  let parsed: unknown
  try {
    parsed = await response.json()
  } catch (error) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE,
      message: '服务端返回了无法解析的响应，请稍后重试。',
      httpStatus: response.status,
      cause: error,
    })
  }

  if (!isApiResult(parsed)) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE,
      message: '服务端返回了不符合契约的响应结构，已停止处理。',
      httpStatus: response.status,
    })
  }

  return parsed as ApiResult<T>
}

function isApiResult(value: unknown): value is ApiResult<unknown> {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' &&
    typeof candidate.message === 'string' &&
    typeof candidate.trace_id === 'string' &&
    typeof candidate.timestamp === 'string' &&
    'data' in candidate
  )
}
