/**
 * 前端统一错误类型（`04` §2.3.2 / 第 7 章）。
 *
 * 两条约定：
 *
 * 1. **服务端错误码原样保留**：`code` 直接取后端信封里的稳定错误码（如 `E-SYS-02`、
 *    `UPSTREAM_TIMEOUT`），前端不得自行改写或翻译成另一套编码，否则前后端对不上号。
 * 2. **纯客户端错误必须带 `CLIENT_` 前缀**：连接失败、响应不可解析这类错误根本没有服务端错误码，
 *    用前缀区分开，避免与 `04` 第 7 章字典里的码混在一起。
 */

export const CLIENT_ERROR_CODES = {
  /** 网络不可达 / 请求被中断，未收到任何 HTTP 响应。 */
  NETWORK: 'CLIENT_NETWORK_ERROR',
  /** 收到了响应但不是合法的 `Result<T>` 信封（如 Nginx 返回的 HTML 错误页）。 */
  MALFORMED_RESPONSE: 'CLIENT_MALFORMED_RESPONSE',
} as const

export interface ApiErrorOptions {
  code: string
  message: string
  /** 服务端追踪号；用于让用户复制给运维排查（`04` §2.3.1）。 */
  traceId?: string | null
  httpStatus?: number | null
  cause?: unknown
}

export class ApiError extends Error {
  readonly code: string
  readonly traceId: string | null
  readonly httpStatus: number | null

  constructor(options: ApiErrorOptions) {
    super(options.message, { cause: options.cause })
    this.name = 'ApiError'
    this.code = options.code
    this.traceId = options.traceId ?? null
    this.httpStatus = options.httpStatus ?? null
  }

  /** 401：登录态失效，调用方应清理本地会话。 */
  get isUnauthorized(): boolean {
    return this.httpStatus === 401
  }

  /** 是否属于纯客户端错误（没有服务端错误码）。 */
  get isClientSide(): boolean {
    return this.code.startsWith('CLIENT_')
  }
}
