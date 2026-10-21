import { ref, watch } from 'vue'
import type { Ref } from 'vue'

import type { ModelProbeResult } from '@/api/user-config'
import type { ModelConfigFormState } from '@/views/settings/model-config-form'

/**
 * 探测结果与保存提示的生命周期。
 *
 * 探测结果只对“产生它时的表单值”有效：任一相关字段变化都必须清空对应结果，并清空保存提示，
 * 否则用户改完地址/模型/Key 后仍会看到旧的“连接成功”，把无效配置伪装成已验证。
 */
export function useModelProbeResults(form: Ref<ModelConfigFormState>) {
  const chatProbe = ref<ModelProbeResult | null>(null)
  const embeddingProbe = ref<ModelProbeResult | null>(null)
  const savedHint = ref('')

  watch(
    [
      () => form.value.chatProvider,
      () => form.value.chatBaseUrl,
      () => form.value.chatModel,
      () => form.value.chatApiKey,
    ],
    () => {
      chatProbe.value = null
      savedHint.value = ''
    },
  )

  watch(
    [
      () => form.value.embeddingProvider,
      () => form.value.embeddingBaseUrl,
      () => form.value.embeddingModel,
      () => form.value.embeddingApiKey,
      () => form.value.reuseChatApiKey,
      // 复用模式下 Embedding 的 Key 来源是对话 Key，改它同样要让 Embedding 探测结果失效
      () => form.value.chatApiKey,
    ],
    () => {
      embeddingProbe.value = null
      savedHint.value = ''
    },
  )

  return { chatProbe, embeddingProbe, savedHint }
}
