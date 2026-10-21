import type {
  ChatProvider,
  EmbeddingProvider,
  ModelConfigUpdateRequest,
  ModelProbeRequest,
  ModelProbeResult,
  UserConfig,
} from '@/api/user-config'

/** 设置页模型表单的纯状态与纯函数；不含任何存储调用，便于单测。 */

export interface ModelConfigFormState {
  chatProvider: ChatProvider
  chatBaseUrl: string
  chatModel: string
  /** 明文只存在于组件内存；永远不从服务端填充。 */
  chatApiKey: string
  hasSavedChatKey: boolean
  chatKeyMasked: string | null
  embeddingProvider: EmbeddingProvider
  embeddingBaseUrl: string
  embeddingModel: string
  embeddingApiKey: string
  hasSavedEmbeddingKey: boolean
  embeddingKeyMasked: string | null
  reuseChatApiKey: boolean
}

/** 仅允许对单一模型发起探测；`BOTH` 需要两套不同的地址，`API-USER-002` 表达不了。 */
export type SingleProbeType = 'CHAT' | 'EMBEDDING'

export function createModelForm(config?: UserConfig | null): ModelConfigFormState {
  return {
    chatProvider: config?.chat_provider ?? 'OPENAI_COMPATIBLE',
    chatBaseUrl: config?.chat_base_url ?? '',
    chatModel: config?.chat_model ?? '',
    chatApiKey: '',
    hasSavedChatKey: config?.has_chat_api_key ?? false,
    chatKeyMasked: config?.chat_api_key_masked ?? null,
    embeddingProvider: config?.default_embedding_provider ?? 'OPENAI_COMPATIBLE',
    embeddingBaseUrl: config?.embedding_base_url ?? '',
    embeddingModel: config?.default_embedding_model ?? '',
    embeddingApiKey: '',
    hasSavedEmbeddingKey: config?.has_embedding_api_key ?? false,
    embeddingKeyMasked: config?.embedding_api_key_masked ?? null,
    reuseChatApiKey: false,
  }
}

export class ModelFormValidationError extends Error {}

/** 服务端是否已有完整有效的对话模型配置；不得只看 `chat_provider` 是否存在。 */
export function isModelConfigured(config: UserConfig | null | undefined): boolean {
  return config?.has_byok_config === true
}

/** 客户端必填校验；服务端仍会做完整校验（长度、SSRF 白名单、Key 规则）。 */
export function validateModelForm(form: ModelConfigFormState): void {
  assertFilled(form.chatBaseUrl, '对话模型 Base URL')
  assertFilled(form.chatModel, '对话模型名称')
  assertFilled(form.embeddingBaseUrl, 'Embedding Base URL')
  assertFilled(form.embeddingModel, 'Embedding 模型名称')
}

/** 只校验本次探测涉及的那一半，避免"只想测对话模型却因 Embedding 未填而被拦下"。 */
export function validateProbeSection(form: ModelConfigFormState, probeType: SingleProbeType): void {
  if (probeType === 'CHAT') {
    assertFilled(form.chatBaseUrl, '对话模型 Base URL')
    assertFilled(form.chatModel, '对话模型名称')
    return
  }
  assertFilled(form.embeddingBaseUrl, 'Embedding Base URL')
  assertFilled(form.embeddingModel, 'Embedding 模型名称')
}

function assertFilled(value: string, label: string): void {
  if (value.trim() === '') {
    throw new ModelFormValidationError(`请填写${label}。`)
  }
}

/**
 * 构造探测请求。
 *
 * - 用户输入了新 Key → 用新 Key；
 * - 未输入但服务端已有 Key → `use_saved_key=true`（后端解密，前端不接触明文）；
 * - 两者都没有 → 不带 Key，交给后端按"非免密供应商必须有 Key"返回可读错误。
 *
 * 勾选"复用对话 Key"时，Embedding 探测必须使用**本次输入的对话 Key**：
 * `API-USER-002` 的 `use_saved_key` 只查 Embedding 列，查不到对话列；
 * 静默回退到旧的 Embedding Key 会用错 Key，因此这里直接拒绝并提示用户。
 */
export function toProbeRequest(form: ModelConfigFormState, probeType: SingleProbeType): ModelProbeRequest {
  const isChat = probeType === 'CHAT'
  const typedKey = normalizeSecret(isChat ? form.chatApiKey : form.embeddingApiKey)
  const hasSavedKey = isChat ? form.hasSavedChatKey : form.hasSavedEmbeddingKey
  let apiKey = typedKey
  let useSavedKey = typedKey === undefined && hasSavedKey

  if (!isChat && form.reuseChatApiKey) {
    const typedChatKey = normalizeSecret(form.chatApiKey)
    if (typedChatKey === undefined) {
      throw new ModelFormValidationError(
        '勾选“复用对话模型的 API Key”时，请先在上方输入一次对话 Key 再单独测试 Embedding；'
          + '也可以直接保存，保存时会用已保存的对话 Key 一并探测。',
      )
    }
    apiKey = typedChatKey
    useSavedKey = false
  }

  const request: ModelProbeRequest = {
    probe_type: probeType,
    provider: isChat ? form.chatProvider : form.embeddingProvider,
    base_url: (isChat ? form.chatBaseUrl : form.embeddingBaseUrl).trim(),
    use_saved_key: useSavedKey,
    model_name: (isChat ? form.chatModel : form.embeddingModel).trim(),
  }
  if (apiKey !== undefined) {
    request.api_key = apiKey
  }
  return request
}

/**
 * 构造保存请求。
 *
 * 空 Key 字段**整个省略**而不是传空串：后端以 `null/缺失` 表示"保留原密文"，
 * 传空串会被当成"把 Key 改成空字符串"。
 */
export function toSaveRequest(form: ModelConfigFormState): ModelConfigUpdateRequest {
  const chatKey = normalizeSecret(form.chatApiKey)
  const embeddingKey = normalizeSecret(form.embeddingApiKey)
  const request: ModelConfigUpdateRequest = {
    chat_provider: form.chatProvider,
    chat_base_url: form.chatBaseUrl.trim(),
    chat_model: form.chatModel.trim(),
    default_embedding_provider: form.embeddingProvider,
    embedding_base_url: form.embeddingBaseUrl.trim(),
    reuse_chat_api_key: form.reuseChatApiKey,
    default_embedding_model: form.embeddingModel.trim(),
  }
  if (chatKey !== undefined) {
    request.chat_api_key = chatKey
  }
  if (!form.reuseChatApiKey && embeddingKey !== undefined) {
    request.embedding_api_key = embeddingKey
  }
  return request
}

/** 密钥状态展示：只回掩码，绝不回明文。 */
export function keyStatusLabel(hasKey: boolean, masked: string | null): string {
  if (!hasKey) {
    return '未配置'
  }
  return masked === null || masked === '' ? '已配置（掩码不可用）' : masked
}

/**
 * 探测是否真正成功。
 *
 * HTTP 200 不等于模型可用：必须 `reachable === true`；Embedding 还要求实测维度 > 0。
 * UI 绝不能只凭“请求没报错”就展示绿色“连接成功”。
 */
export function isProbeOutcomeSuccessful(
  outcome: ModelProbeResult,
  probeType: SingleProbeType,
): boolean {
  if (outcome.reachable !== true) {
    return false
  }
  if (probeType === 'CHAT') {
    return true
  }
  return outcome.actual_dim !== null && outcome.actual_dim > 0
}

function normalizeSecret(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}
