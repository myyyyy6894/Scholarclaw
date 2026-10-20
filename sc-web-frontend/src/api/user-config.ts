import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/**
 * 用户配置与 BYOK 接口（`04` §4.2 `API-USER-001~005`）。
 *
 * **密钥纪律**：明文 Key 只允许出现在本模块发起的请求体里；不写入存储、不进 URL、
 * 不在页面之间传递。页面只展示服务端返回的 `*_api_key_masked`。
 */

export type ChatProvider = 'OPENAI_COMPATIBLE' | 'DEEPSEEK' | 'ZHIPU' | 'OLLAMA'
export type EmbeddingProvider = 'OPENAI_COMPATIBLE' | 'ZHIPU' | 'OLLAMA'
export type ModelProvider = ChatProvider | EmbeddingProvider
export type ProbeType = 'CHAT' | 'EMBEDDING' | 'BOTH'
export type ImPlatform = 'QQ_ONEBOT' | 'WECOM'
export type ClearKeysTarget = 'ALL' | 'CHAT' | 'EMBEDDING'

export interface UserConfig {
  user_id: BigIntId
  username: string
  chat_provider: ChatProvider | null
  chat_base_url: string | null
  has_chat_api_key: boolean
  chat_api_key_masked: string | null
  chat_model: string | null
  default_embedding_provider: EmbeddingProvider | null
  embedding_base_url: string | null
  has_embedding_api_key: boolean
  embedding_api_key_masked: string | null
  default_embedding_model: string | null
  default_embedding_dim: number | null
  timezone: string
  dnd_start_time: string
  dnd_end_time: string
  bound_im_platform: ImPlatform | null
  bound_im_uid: string | null
  updated_at: string
  /** 仅 `API-USER-003` 返回；`API-USER-001` 无此字段。 */
  existing_course_count?: number
}

export interface ModelProbeRequest {
  probe_type: ProbeType
  provider: ModelProvider
  base_url: string
  api_key?: string
  use_saved_key: boolean
  model_name: string
}

export interface ModelProbeResult {
  reachable: boolean
  probe_type: ProbeType
  model_name: string
  actual_dim: number | null
  latency_ms: number
}

export interface ModelConfigUpdateRequest {
  chat_provider: ChatProvider
  chat_base_url: string
  chat_api_key?: string
  chat_model: string
  default_embedding_provider: EmbeddingProvider
  embedding_base_url: string
  reuse_chat_api_key: boolean
  embedding_api_key?: string
  default_embedding_model: string
}

export interface PreferencesUpdateRequest {
  timezone: string
  dnd_start_time: string
  dnd_end_time: string
  bound_im_platform: ImPlatform | null
  bound_im_uid: string | null
}

export function getUserConfig(): Promise<UserConfig> {
  return apiRequest<UserConfig>('/v1/user/config')
}

/** 只读探测：不写库；`use_saved_key=true` 时由后端解密已保存 Key。 */
export function probeModel(request: ModelProbeRequest): Promise<ModelProbeResult> {
  return apiRequest<ModelProbeResult>('/v1/user/model-config/probe', {
    method: 'POST',
    body: request,
  })
}

export function saveModelConfig(request: ModelConfigUpdateRequest): Promise<UserConfig> {
  return apiRequest<UserConfig>('/v1/user/model-config', { method: 'PUT', body: request })
}

export function updatePreferences(request: PreferencesUpdateRequest): Promise<UserConfig> {
  return apiRequest<UserConfig>('/v1/user/preferences', { method: 'PUT', body: request })
}

export function clearApiKeys(target: ClearKeysTarget = 'ALL'): Promise<UserConfig> {
  return apiRequest<UserConfig>(`/v1/user/model-config/keys?target=${target}`, { method: 'DELETE' })
}
