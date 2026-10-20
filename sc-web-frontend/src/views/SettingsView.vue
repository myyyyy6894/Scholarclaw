<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import {
  clearApiKeys,
  getUserConfig,
  probeModel,
  saveModelConfig,
  updatePreferences,
} from '@/api/user-config'
import type {
  ChatProvider,
  ClearKeysTarget,
  EmbeddingProvider,
  ModelProbeResult,
  UserConfig,
} from '@/api/user-config'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import {
  createModelForm,
  isProbeOutcomeSuccessful,
  keyStatusLabel,
  ModelFormValidationError,
  toProbeRequest,
  toSaveRequest,
  validateModelForm,
  validateProbeSection,
} from '@/views/settings/model-config-form'
import type { ModelConfigFormState, SingleProbeType } from '@/views/settings/model-config-form'
import {
  createPreferencesForm,
  PreferencesValidationError,
  toPreferencesRequest,
} from '@/views/settings/preferences-form'
import type { PreferencesFormState } from '@/views/settings/preferences-form'

/**
 * 个人设置页（`06` TASK-205、`04` §4.2）。
 *
 * 页面职责：展示服务端返回的掩码、提交用户输入的候选配置、把错误原文（后端已脱敏）呈现给用户。
 * **明文 Key 只存在于本组件的内存状态**，保存成功后立即清空；页面不读写任何浏览器存储。
 */

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const loadError = ref('')
const config = ref<UserConfig | null>(null)

const modelForm = ref<ModelConfigFormState>(createModelForm())
const prefsForm = ref<PreferencesFormState>(createPreferencesForm())

const savingModel = ref(false)
const savingPrefs = ref(false)
const probingChat = ref(false)
const probingEmbedding = ref(false)
const chatProbe = ref<ModelProbeResult | null>(null)
const embeddingProbe = ref<ModelProbeResult | null>(null)
const clearingTarget = ref<ClearKeysTarget | null>(null)
const savedHint = ref('')

const CHAT_PROVIDERS: Array<{ value: ChatProvider; label: string }> = [
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'ZHIPU', label: '智谱 GLM' },
  { value: 'OLLAMA', label: 'Ollama（本地/自建）' },
]

const EMBEDDING_PROVIDERS: Array<{ value: EmbeddingProvider; label: string }> = [
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'ZHIPU', label: '智谱 GLM' },
  { value: 'OLLAMA', label: 'Ollama（本地/自建）' },
]

const IM_PLATFORMS = [
  { value: 'QQ_ONEBOT', label: 'QQ（OneBot）' },
  { value: 'WECOM', label: '企业微信' },
]

const chatKeyPlaceholder = computed(() =>
  modelForm.value.hasSavedChatKey
    ? `已保存：${keyStatusLabel(true, modelForm.value.chatKeyMasked)}（留空表示不修改）`
    : '请输入对话模型 API Key',
)

const embeddingKeyPlaceholder = computed(() =>
  modelForm.value.hasSavedEmbeddingKey
    ? `已保存：${keyStatusLabel(true, modelForm.value.embeddingKeyMasked)}（留空表示不修改）`
    : '请输入 Embedding API Key',
)

const hasAnyConfig = computed(
  () => config.value?.chat_provider !== null && config.value?.chat_provider !== undefined,
)

// 探测结果只对“产生它时的表单值”有效：任何相关字段变更都必须清空，
// 否则用户改完地址/模型/Key 后仍会看到旧的“连接成功”，把无效配置伪装成已验证。
watch(
  [
    () => modelForm.value.chatProvider,
    () => modelForm.value.chatBaseUrl,
    () => modelForm.value.chatModel,
    () => modelForm.value.chatApiKey,
  ],
  () => {
    chatProbe.value = null
  },
)

watch(
  [
    () => modelForm.value.embeddingProvider,
    () => modelForm.value.embeddingBaseUrl,
    () => modelForm.value.embeddingModel,
    () => modelForm.value.embeddingApiKey,
    () => modelForm.value.reuseChatApiKey,
    // 复用模式下 Embedding 的 Key 来源是对话 Key，改它同样要让 Embedding 探测结果失效
    () => modelForm.value.chatApiKey,
  ],
  () => {
    embeddingProbe.value = null
  },
)

onMounted(() => {
  void loadConfig()
})

async function loadConfig(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const data = await getUserConfig()
    applyConfig(data, { resetSecrets: true })
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '配置读取失败，请稍后重试。'
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function applyConfig(data: UserConfig, options: { resetSecrets: boolean }): void {
  config.value = data
  if (options.resetSecrets) {
    modelForm.value = createModelForm(data)
    prefsForm.value = createPreferencesForm(data)
    chatProbe.value = null
    embeddingProbe.value = null
    return
  }
  // 只刷新服务端状态（掩码/已保存标志），保留用户尚未提交的输入
  modelForm.value.hasSavedChatKey = data.has_chat_api_key
  modelForm.value.chatKeyMasked = data.chat_api_key_masked
  modelForm.value.hasSavedEmbeddingKey = data.has_embedding_api_key
  modelForm.value.embeddingKeyMasked = data.embedding_api_key_masked
}

function onProbe(type: SingleProbeType): void {
  void runProbe(type)
}

async function runProbe(type: SingleProbeType): Promise<void> {
  const busy = type === 'CHAT' ? probingChat : probingEmbedding
  const result = type === 'CHAT' ? chatProbe : embeddingProbe
  busy.value = true
  result.value = null
  try {
    validateProbeSection(modelForm.value, type)
    const outcome = await probeModel(toProbeRequest(modelForm.value, type))
    if (!isProbeOutcomeSuccessful(outcome, type)) {
      // HTTP 成功不等于模型可用；不得把 reachable=false / 维度缺失显示成“连接成功”
      result.value = null
      notifyWarning('探测未通过：请检查 Base URL、API Key 与模型名称。')
      return
    }
    result.value = outcome
    const dim = outcome.actual_dim === null ? '' : `，实测维度 ${outcome.actual_dim}`
    notifySuccess(`连接成功（${outcome.latency_ms} ms${dim}）`)
  } catch (error) {
    if (error instanceof ModelFormValidationError) {
      notifyWarning(error.message)
    } else {
      notifyError(error)
    }
  } finally {
    busy.value = false
  }
}

function onSaveModel(): void {
  void saveModel()
}

async function saveModel(): Promise<void> {
  if (savingModel.value) {
    return
  }
  try {
    validateModelForm(modelForm.value)
  } catch (error) {
    if (error instanceof ModelFormValidationError) {
      notifyWarning(error.message)
      return
    }
    throw error
  }
  savingModel.value = true
  savedHint.value = ''
  try {
    const updated = await saveModelConfig(toSaveRequest(modelForm.value))
    applyConfig(updated, { resetSecrets: true })
    savedHint.value = `已保存 · 实测向量维度 ${updated.default_embedding_dim ?? '—'}`
    notifySuccess('模型配置已保存')
    if ((updated.existing_course_count ?? 0) > 0) {
      notifyWarning(
        `已有 ${updated.existing_course_count ?? 0} 门课程仍使用旧模型；如需切换，请在课程管理中单独操作。`,
      )
    }
  } catch (error) {
    notifyError(error)
  } finally {
    savingModel.value = false
  }
}

function onSavePreferences(): void {
  void savePreferences()
}

async function savePreferences(): Promise<void> {
  if (savingPrefs.value) {
    return
  }
  savingPrefs.value = true
  try {
    const updated = await updatePreferences(toPreferencesRequest(prefsForm.value))
    config.value = updated
    prefsForm.value = createPreferencesForm(updated)
    notifySuccess('个人偏好已更新')
  } catch (error) {
    if (error instanceof PreferencesValidationError) {
      notifyWarning(error.message)
    } else {
      notifyError(error)
    }
  } finally {
    savingPrefs.value = false
  }
}

function onClearKeys(target: ClearKeysTarget): void {
  void clearKeys(target)
}

async function clearKeys(target: ClearKeysTarget): Promise<void> {
  if (clearingTarget.value !== null) {
    return
  }
  const label = target === 'ALL' ? '全部 API Key' : target === 'CHAT' ? '对话模型 API Key' : 'Embedding API Key'
  try {
    await ElMessageBox.confirm(
      `确定销毁${label}？销毁后相关 AI 功能需重新配置，且无法恢复。`,
      '销毁 API Key',
      { type: 'warning', confirmButtonText: '销毁', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  clearingTarget.value = target
  try {
    const updated = await clearApiKeys(target)
    applyConfig(updated, { resetSecrets: false })
    // 已保存 Key 被销毁后，旧探测结果不再可信，必须立即清空
    if (target === 'CHAT' || target === 'ALL') {
      chatProbe.value = null
    }
    if (target === 'EMBEDDING' || target === 'ALL') {
      embeddingProbe.value = null
    }
    savedHint.value = ''
    notifySuccess(`已销毁${label}`)
  } catch (error) {
    notifyError(error)
  } finally {
    clearingTarget.value = null
  }
}

function goToLogin(): void {
  auth.clearSession()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <div>
    <el-skeleton v-if="loading" :rows="6" animated />

    <el-result
      v-else-if="loadError !== ''"
      icon="error"
      title="配置读取失败"
      :sub-title="loadError"
    >
      <template #extra>
        <el-button type="primary" @click="loadConfig">重新加载</el-button>
        <el-button @click="goToLogin">重新登录</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-card class="sc-card-gap" shadow="never">
        <template #header>
          <div class="sc-settings__header">
            <span>个人设置</span>
            <span class="sc-settings__meta">
              {{ config?.username }} · user_id {{ config?.user_id }}
            </span>
          </div>
        </template>

        <el-alert
          v-if="!hasAnyConfig"
          type="info"
          :closable="false"
          show-icon
          title="尚未配置模型"
          description="填写对话模型与默认 Embedding 模型后点击“测试连接”，确认可用再保存。保存时会重新探测并回填真实向量维度。"
        />
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          :title="`已配置模型：${config?.chat_model ?? ''}`"
          :description="`当前默认向量维度：${config?.default_embedding_dim ?? '未探测'}`"
        />
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>BYOK 模型配置</template>

        <el-form label-position="top">
          <el-divider content-position="left">对话模型</el-divider>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <el-form-item label="供应商">
                <el-select v-model="modelForm.chatProvider" class="sc-settings__full">
                  <el-option
                    v-for="item in CHAT_PROVIDERS"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="16">
              <el-form-item label="Base URL">
                <el-input v-model="modelForm.chatBaseUrl" placeholder="https://api.deepseek.com/v1" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12">
              <el-form-item label="模型名称">
                <el-input v-model="modelForm.chatModel" placeholder="deepseek-chat" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item label="API Key">
                <el-input
                  v-model="modelForm.chatApiKey"
                  type="password"
                  show-password
                  autocomplete="off"
                  :placeholder="chatKeyPlaceholder"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <div class="sc-settings__row">
            <el-button :loading="probingChat" @click="onProbe('CHAT')">测试对话模型</el-button>
            <el-tag v-if="chatProbe !== null" type="success">
              延迟 {{ chatProbe.latency_ms }} ms
            </el-tag>
            <span class="sc-settings__hint">当前已保存：{{ keyStatusLabel(modelForm.hasSavedChatKey, modelForm.chatKeyMasked) }}</span>
          </div>

          <el-divider content-position="left">默认 Embedding 模型</el-divider>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <el-form-item label="供应商">
                <el-select v-model="modelForm.embeddingProvider" class="sc-settings__full">
                  <el-option
                    v-for="item in EMBEDDING_PROVIDERS"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="16">
              <el-form-item label="Base URL">
                <el-input v-model="modelForm.embeddingBaseUrl" placeholder="https://api.siliconflow.cn/v1" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12">
              <el-form-item label="模型名称">
                <el-input v-model="modelForm.embeddingModel" placeholder="BAAI/bge-m3" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item label="API Key">
                <el-input
                  v-model="modelForm.embeddingApiKey"
                  type="password"
                  show-password
                  autocomplete="off"
                  :disabled="modelForm.reuseChatApiKey"
                  :placeholder="modelForm.reuseChatApiKey ? '已复用对话模型 Key' : embeddingKeyPlaceholder"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-checkbox v-model="modelForm.reuseChatApiKey" class="sc-settings__checkbox">
            复用对话模型的 API Key
          </el-checkbox>
          <div v-if="modelForm.reuseChatApiKey" class="sc-settings__hint sc-settings__hint--block">
            单独测试 Embedding 前需先在上方输入一次对话 Key；直接保存时会自动复用已保存的对话 Key。
          </div>
          <div class="sc-settings__row">
            <el-button :loading="probingEmbedding" @click="onProbe('EMBEDDING')">
              测试 Embedding 模型
            </el-button>
            <el-tag v-if="embeddingProbe !== null" type="success">
              延迟 {{ embeddingProbe.latency_ms }} ms · 维度 {{ embeddingProbe.actual_dim ?? '—' }}
            </el-tag>
            <span class="sc-settings__hint">
              当前已保存：{{ keyStatusLabel(modelForm.hasSavedEmbeddingKey, modelForm.embeddingKeyMasked) }}
            </span>
          </div>

          <div class="sc-settings__row sc-settings__row--end">
            <div class="sc-settings__hint-group">
              <span class="sc-settings__hint">
                保存时后端会重新探测并以实测维度回填（前端不提交维度）。
              </span>
              <span class="sc-settings__hint">{{ savedHint }}</span>
            </div>
            <el-button type="primary" :loading="savingModel" @click="onSaveModel">
              保存模型配置
            </el-button>
          </div>
        </el-form>
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>个人偏好与 IM 绑定</template>
        <el-form label-position="top">
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <el-form-item label="IANA 时区">
                <el-input v-model="prefsForm.timezone" placeholder="Asia/Shanghai" />
              </el-form-item>
            </el-col>
            <el-col :xs="12" :sm="8">
              <el-form-item label="免打扰开始">
                <el-time-picker
                  v-model="prefsForm.dndStartTime"
                  class="sc-settings__full"
                  format="HH:mm"
                  value-format="HH:mm:ss"
                  placeholder="23:30"
                />
              </el-form-item>
            </el-col>
            <el-col :xs="12" :sm="8">
              <el-form-item label="免打扰结束">
                <el-time-picker
                  v-model="prefsForm.dndEndTime"
                  class="sc-settings__full"
                  format="HH:mm"
                  value-format="HH:mm:ss"
                  placeholder="07:30"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <el-form-item label="IM 平台（可选）">
                <el-select v-model="prefsForm.imPlatform" clearable class="sc-settings__full" placeholder="不绑定">
                  <el-option
                    v-for="item in IM_PLATFORMS"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="16">
              <el-form-item label="IM 账号标识（可选）">
                <el-input v-model="prefsForm.imUid" placeholder="例如 QQ 号 24681012" />
              </el-form-item>
            </el-col>
          </el-row>
          <div class="sc-settings__row sc-settings__row--end">
            <span class="sc-settings__hint">平台与账号必须同时填写或同时留空。</span>
            <el-button type="primary" :loading="savingPrefs" @click="onSavePreferences">
              保存偏好
            </el-button>
          </div>
        </el-form>
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>密钥销毁</template>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="销毁后不可恢复"
          description="销毁只会清空服务端保存的密文，不会影响你输入的草稿；下次使用 AI 功能前需要重新配置。"
        />
        <div class="sc-settings__row sc-settings__row--end">
          <div class="sc-settings__row">
            <el-button
              :loading="clearingTarget === 'CHAT'"
              :disabled="clearingTarget !== null"
              @click="onClearKeys('CHAT')"
            >
              销毁对话 Key
            </el-button>
            <el-button
              :loading="clearingTarget === 'EMBEDDING'"
              :disabled="clearingTarget !== null"
              @click="onClearKeys('EMBEDDING')"
            >
              销毁 Embedding Key
            </el-button>
          </div>
          <el-button
            type="danger"
            plain
            :loading="clearingTarget === 'ALL'"
            :disabled="clearingTarget !== null"
            @click="onClearKeys('ALL')"
          >
            销毁全部 Key
          </el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.sc-settings__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sc-settings__meta {
  color: #909399;
  font-size: 13px;
  font-weight: 400;
}

.sc-settings__full {
  width: 100%;
}

.sc-settings__row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 16px;
  flex-wrap: wrap;
}

.sc-settings__row--end {
  justify-content: space-between;
  margin-top: 8px;
}

.sc-settings__hint {
  color: #909399;
  font-size: 13px;
}

.sc-settings__hint--block {
  display: block;
  margin: -8px 0 16px;
}

.sc-settings__hint-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sc-settings__checkbox {
  margin-bottom: 16px;
}
</style>
