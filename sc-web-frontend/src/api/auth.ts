import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/**
 * 认证接口（`04` §4.1 `API-AUTH-001/002/003`）。
 *
 * 字段名保持 `snake_case`，与后端信封原样对应；这里不做驼峰转换，避免转换层静默丢字段。
 */

export interface AuthResponse {
  user_id: BigIntId
  username: string
  account_status: string
  access_token: string
  token_type: string
  expires_in: number
  has_byok_config: boolean
  created_at: string
}

export interface RegisterRequest {
  username: string
  password: string
  /** IANA 时区；缺省时后端按 `Asia/Shanghai` 初始化。 */
  timezone?: string
}

export interface LoginRequest {
  username: string
  password: string
}

/** 注册：成功返回 201 + 已签发 token（`04` `API-CONF-01` 方案 A）。 */
export function register(request: RegisterRequest): Promise<AuthResponse> {
  return apiRequest<AuthResponse>('/v1/auth/register', { method: 'POST', body: request })
}

export function login(request: LoginRequest): Promise<AuthResponse> {
  return apiRequest<AuthResponse>('/v1/auth/login', { method: 'POST', body: request })
}

/** 登出：吊销当前 JWT。即使失败，调用方也应清理本地会话。 */
export async function logout(): Promise<void> {
  await apiRequest<null>('/v1/auth/logout', { method: 'POST' })
}

/**
 * 只把会话必需的两项交给 store：`access_token` 与展示用的 `user_id/username`。
 *
 * **刻意不把整个 `AuthResponse` 存进浏览器存储**：响应里还有 `account_status` 等字段，
 * 未来若加入更多敏感字段，整体落盘会把它们一起带进持久化存储。
 */
export function toAuthSession(response: AuthResponse): {
  token: string
  user: { user_id: BigIntId; username: string }
} {
  return {
    token: response.access_token,
    user: { user_id: response.user_id, username: response.username },
  }
}
