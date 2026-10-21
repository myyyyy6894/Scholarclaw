import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'

import type { ModelProbeResult, UserConfig } from '@/api/user-config'
import {
  createModelForm,
  isModelConfigured,
  isProbeOutcomeSuccessful,
  keyStatusLabel,
  ModelFormValidationError,
  toProbeRequest,
  toSaveRequest,
  validateModelForm,
  validateProbeSection,
} from '@/views/settings/model-config-form'
import { useModelProbeResults } from '@/views/settings/use-model-probe-results'
import {
  createPreferencesForm,
  PreferencesValidationError,
  toPreferencesRequest,
} from '@/views/settings/preferences-form'

/**
 * 设置页表单的纯逻辑（`04` §4.2）。
 *
 * 这些函数决定"提交什么"，因此重点锁住：空 Key 必须省略（后端据此保留原密文）、
 * `use_saved_key` 的正确语义、以及时间/IM 字段的契约格式。
 */

const CONFIG: UserConfig = {
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
  has_byok_config: true,
  timezone: 'Asia/Shanghai',
  dnd_start_time: '23:30:00',
  dnd_end_time: '07:30:00',
  bound_im_platform: 'QQ_ONEBOT',
  bound_im_uid: '24681012',
  updated_at: '2026-10-20T00:00:00.000+08:00',
}

describe('model config form', () => {
  it('从服务端配置初始化表单，但绝不回填明文 Key', () => {
    const form = createModelForm(CONFIG)

    expect(form.chatProvider).toBe('DEEPSEEK')
    expect(form.chatBaseUrl).toBe('https://api.deepseek.com/v1')
    expect(form.chatKeyMasked).toBe('sk-****8a9f')
    expect(form.chatApiKey).toBe('')
    expect(form.embeddingApiKey).toBe('')
    expect(form.hasSavedChatKey).toBe(true)
  })

  it('空配置使用中性的供应商默认值，但不伪造 URL/模型名', () => {
    const form = createModelForm(null)

    expect(form.chatBaseUrl).toBe('')
    expect(form.chatModel).toBe('')
    expect(form.embeddingBaseUrl).toBe('')
    expect(form.hasSavedChatKey).toBe(false)
  })

  it('必填校验覆盖对话与 Embedding 两段', () => {
    const form = createModelForm(CONFIG)
    expect(() => validateModelForm(form)).not.toThrow()

    form.embeddingModel = '  '
    expect(() => validateModelForm(form)).toThrow(ModelFormValidationError)

    expect(() => validateProbeSection(form, 'CHAT')).not.toThrow()
    expect(() => validateProbeSection(form, 'EMBEDDING')).toThrow(ModelFormValidationError)
  })

  it('未输入 Key 且已有保存 Key 时使用 use_saved_key，且不发送 api_key', () => {
    const request = toProbeRequest(createModelForm(CONFIG), 'CHAT')

    expect(request.use_saved_key).toBe(true)
    expect('api_key' in request).toBe(false)
    expect(request.probe_type).toBe('CHAT')
    expect(request.provider).toBe('DEEPSEEK')
  })

  it('输入新 Key 时优先使用它，并关闭 use_saved_key', () => {
    const form = createModelForm(CONFIG)
    form.chatApiKey = ' sk-new-value '

    const request = toProbeRequest(form, 'CHAT')

    expect(request.api_key).toBe('sk-new-value')
    expect(request.use_saved_key).toBe(false)
  })

  it('没有已保存 Key 时 use_saved_key=false，把 Key 规则交给后端判定', () => {
    const form = createModelForm({ ...CONFIG, has_chat_api_key: false, chat_api_key_masked: null })

    const request = toProbeRequest(form, 'CHAT')

    expect(request.use_saved_key).toBe(false)
    expect('api_key' in request).toBe(false)
  })

  it('无 Key 重新探测不会伪装成使用已保存 Key', () => {
    const form = createModelForm({ ...CONFIG, has_chat_api_key: false, chat_api_key_masked: null })

    const request = toProbeRequest(form, 'CHAT')

    expect(request.use_saved_key).toBe(false)
    expect(request.api_key).toBeUndefined()
  })

  it('“已配置模型”以服务端 has_byok_config 为准，部分配置不算已配置', () => {
    expect(isModelConfigured(CONFIG)).toBe(true)
    expect(isModelConfigured({ ...CONFIG, has_byok_config: false })).toBe(false)
    expect(isModelConfigured({ ...CONFIG, has_byok_config: false, chat_base_url: null })).toBe(false)
    expect(isModelConfigured(null)).toBe(false)
  })

  it('复用对话 Key 时，Embedding 探测使用本次输入的对话 Key', () => {
    const form = createModelForm(CONFIG)
    form.reuseChatApiKey = true
    form.chatApiKey = 'sk-chat-typed-value'

    const request = toProbeRequest(form, 'EMBEDDING')

    expect(request.api_key).toBe('sk-chat-typed-value')
    expect(request.use_saved_key).toBe(false)
  })

  it('复用对话 Key 但未输入时，拒绝单独测试 Embedding，不回退到旧 Embedding Key', () => {
    const form = createModelForm(CONFIG)
    form.reuseChatApiKey = true

    expect(() => toProbeRequest(form, 'EMBEDDING')).toThrow(ModelFormValidationError)
  })

  it('保存时空 Key 整个省略，避免被后端当成"改成空字符串"', () => {
    const request = toSaveRequest(createModelForm(CONFIG))

    expect('chat_api_key' in request).toBe(false)
    expect('embedding_api_key' in request).toBe(false)
    expect(request.reuse_chat_api_key).toBe(false)
  })

  it('复用对话 Key 时不提交 Embedding Key', () => {
    const form = createModelForm(CONFIG)
    form.reuseChatApiKey = true
    form.embeddingApiKey = 'sk-should-be-ignored'

    const request = toSaveRequest(form)

    expect(request.reuse_chat_api_key).toBe(true)
    expect('embedding_api_key' in request).toBe(false)
  })

  it('掩码展示只回服务端掩码；未配置时明确显示"未配置"', () => {
    expect(keyStatusLabel(true, 'sk-****8a9f')).toBe('sk-****8a9f')
    expect(keyStatusLabel(true, null)).toBe('已配置（掩码不可用）')
    expect(keyStatusLabel(false, null)).toBe('未配置')
  })

  it('探测成功判定：reachable=false 不算成功', () => {
    const outcome: ModelProbeResult = {
      reachable: false,
      probe_type: 'CHAT',
      model_name: 'x',
      actual_dim: null,
      latency_ms: 1,
    }

    expect(isProbeOutcomeSuccessful(outcome, 'CHAT')).toBe(false)
  })

  it('Embedding 只有 reachable=true 且 actual_dim > 0 才算成功', () => {
    const base: ModelProbeResult = {
      reachable: true,
      probe_type: 'EMBEDDING',
      model_name: 'x',
      actual_dim: null,
      latency_ms: 1,
    }

    expect(isProbeOutcomeSuccessful(base, 'EMBEDDING')).toBe(false)
    expect(isProbeOutcomeSuccessful({ ...base, actual_dim: 0 }, 'EMBEDDING')).toBe(false)
    expect(isProbeOutcomeSuccessful({ ...base, actual_dim: 8 }, 'EMBEDDING')).toBe(true)
  })

  it('CHAT 只要 reachable=true 即可（维度为 null 是正常的）', () => {
    const outcome: ModelProbeResult = {
      reachable: true,
      probe_type: 'CHAT',
      model_name: 'x',
      actual_dim: null,
      latency_ms: 1,
    }

    expect(isProbeOutcomeSuccessful(outcome, 'CHAT')).toBe(true)
  })
})

describe('probe result invalidation', () => {
  const SUCCESS: ModelProbeResult = {
    reachable: true,
    probe_type: 'CHAT',
    model_name: 'deepseek-chat',
    actual_dim: null,
    latency_ms: 10,
  }

  it('任一相关字段变更清空对应探测结果与保存提示', async () => {
    const form = ref(createModelForm(CONFIG))
    const { chatProbe, embeddingProbe, savedHint } = useModelProbeResults(form)
    chatProbe.value = { ...SUCCESS }
    embeddingProbe.value = { ...SUCCESS, probe_type: 'EMBEDDING', actual_dim: 8 }
    savedHint.value = '已保存'

    form.value.chatModel = 'DeepSeek-Chat'
    await nextTick()

    expect(chatProbe.value).toBeNull()
    expect(embeddingProbe.value).not.toBeNull()
    expect(savedHint.value).toBe('')

    form.value.embeddingModel = 'another-embedding'
    await nextTick()

    expect(embeddingProbe.value).toBeNull()
  })

  it('复用对话 Key 时，修改对话 Key 也会让 Embedding 探测结果失效', async () => {
    const form = ref(createModelForm(CONFIG))
    form.value.reuseChatApiKey = true
    const { embeddingProbe } = useModelProbeResults(form)
    embeddingProbe.value = { ...SUCCESS, probe_type: 'EMBEDDING', actual_dim: 8 }

    form.value.chatApiKey = 'sk-new-chat-key'
    await nextTick()

    expect(embeddingProbe.value).toBeNull()
  })
})

describe('preferences form', () => {
  it('从服务端配置初始化，并原样保留 HH:mm:ss', () => {
    const form = createPreferencesForm(CONFIG)

    expect(form.timezone).toBe('Asia/Shanghai')
    expect(form.dndStartTime).toBe('23:30:00')
    expect(form.imPlatform).toBe('QQ_ONEBOT')
    expect(form.imUid).toBe('24681012')
  })

  it('HH:mm 自动补齐为 HH:mm:ss', () => {
    const form = createPreferencesForm(CONFIG)
    form.dndStartTime = '22:15'
    form.dndEndTime = '06:45'

    const request = toPreferencesRequest(form)

    expect(request.dnd_start_time).toBe('22:15:00')
    expect(request.dnd_end_time).toBe('06:45:00')
  })

  it('IM 平台与账号必须成对，否则抛可读错误', () => {
    const form = createPreferencesForm(CONFIG)
    form.imUid = ''

    expect(() => toPreferencesRequest(form)).toThrow(PreferencesValidationError)
  })

  it('两者都留空表示解绑，请求中为 null', () => {
    const form = createPreferencesForm(CONFIG)
    form.imPlatform = ''
    form.imUid = '   '

    const request = toPreferencesRequest(form)

    expect(request.bound_im_platform).toBeNull()
    expect(request.bound_im_uid).toBeNull()
  })

  it('非法时间格式被拒绝', () => {
    const form = createPreferencesForm(CONFIG)
    form.dndStartTime = '23:3'

    expect(() => toPreferencesRequest(form)).toThrow(PreferencesValidationError)
  })
})
