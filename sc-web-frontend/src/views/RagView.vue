<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

import { ElMessageBox } from 'element-plus'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { ApiError } from '@/api/error'
import { clearMessages, listMessages, streamRagQa } from '@/api/rag'
import type { CitationPayload, DonePayload, RagCitation } from '@/api/rag'
import { useCourseStore } from '@/stores/course'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import {
  applyDone,
  applyInterrupted,
  applyRejection,
  applyToken,
  citationPageLabel,
  citationScoreLabel,
  createLocalMessage,
  formatDuration,
  historyToUiMessages,
  queryError,
  type UiMessage,
} from '@/views/rag/rag-chat'

/**
 * RAG 溯源问答工作台（`06` TASK-605、`04` §4.5）。
 *
 * 只展示后端真实返回的引用与状态：引用卡片来自 `citation` 事件/会话历史，
 * 断流或错误会明确标为“未完成”，不会把半截回答当作成功。
 */

const courseStore = useCourseStore()

const courses = ref<CourseNamespace[]>([])
const selectedCourseId = ref<string | null>(courseStore.selectedCourseId)
const messages = ref<UiMessage[]>([])
const query = ref('')
const loadingCourses = ref(true)
const loadingHistory = ref(false)
const historyError = ref('')
const courseError = ref('')
const streamError = ref('')
const streaming = ref(false)
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
const messagesEl = ref<HTMLElement | null>(null)

let streamController: AbortController | null = null
let historySeq = 0

const selectedCourse = computed(
  () => courses.value.find((course) => course.course_id === selectedCourseId.value) ?? null,
)
const canSend = computed(
  () => selectedCourseId.value !== null && !streaming.value && query.value.trim() !== '',
)

onMounted(() => {
  void bootstrap()
})

onUnmounted(() => {
  streamController?.abort()
})

async function bootstrap(): Promise<void> {
  loadingCourses.value = true
  courseError.value = ''
  try {
    courses.value = await listCourses()
    const exists = courses.value.some(
      (course) => course.course_id === selectedCourseId.value,
    )
    if (!exists) {
      selectedCourseId.value = courses.value[0]?.course_id ?? null
    }
    courseStore.select(selectedCourseId.value)
    if (selectedCourseId.value !== null) {
      await loadHistory(true)
    }
  } catch (error) {
    courseError.value = error instanceof Error ? error.message : '课程列表读取失败。'
    notifyError(error)
  } finally {
    loadingCourses.value = false
  }
}

function handleCourseChange(): void {
  void onCourseChange()
}

async function onCourseChange(): Promise<void> {
  stopStreaming()
  courseStore.select(selectedCourseId.value)
  messages.value = []
  nextCursor.value = null
  hasMore.value = false
  historyError.value = ''
  streamError.value = ''
  if (selectedCourseId.value !== null) {
    await loadHistory(true)
  }
}

async function loadHistory(reset: boolean): Promise<void> {
  const courseId = selectedCourseId.value
  if (courseId === null) {
    messages.value = []
    return
  }
  const seq = ++historySeq
  loadingHistory.value = true
  historyError.value = ''
  try {
    const page = await listMessages(
      courseId,
      reset ? undefined : nextCursor.value,
    )
    if (seq !== historySeq || courseId !== selectedCourseId.value) {
      return
    }
    const mapped = historyToUiMessages(page.items)
    messages.value = reset ? mapped : [...mapped, ...messages.value]
    nextCursor.value = page.next_cursor
    hasMore.value = page.has_more
    await scrollToBottom()
  } catch (error) {
    if (seq !== historySeq || courseId !== selectedCourseId.value) {
      return
    }
    historyError.value = error instanceof Error ? error.message : '会话历史读取失败。'
    if (reset && messages.value.length === 0) {
      notifyError(error)
    }
  } finally {
    if (seq === historySeq) {
      loadingHistory.value = false
    }
  }
}

function retryHistory(): void {
  void loadHistory(true)
}

function submitQuery(): void {
  void send()
}

function onClearClick(): void {
  void clearHistory()
}

function loadOlder(): void {
  if (!hasMore.value || loadingHistory.value) {
    return
  }
  void loadHistory(false)
}

async function send(): Promise<void> {
  if (streaming.value) {
    return
  }
  const error = queryError(query.value)
  if (error !== null) {
    notifyWarning(error)
    return
  }
  const courseId = selectedCourseId.value
  if (courseId === null) {
    notifyWarning('请先选择课程。')
    return
  }

  const content = query.value.trim()
  query.value = ''
  streamError.value = ''
  messages.value.push(createLocalMessage('USER', content))
  messages.value.push(createLocalMessage('ASSISTANT'))
  const userMessage = messages.value[messages.value.length - 2]
  const assistantMessage = messages.value[messages.value.length - 1]

  const controller = new AbortController()
  streamController = controller
  streaming.value = true
  let doneReceived = false
  let terminalReceived = false
  await scrollToBottom()

  try {
    await streamRagQa(
      {
        courseId,
        query: content,
        clientMsgId: newClientMessageId(),
      },
      {
        onCitation: (payload: CitationPayload) => {
          userMessage.messageId = payload.user_message_id
          assistantMessage.citations = payload.citations
        },
        onRejectionNotice: (payload) => {
          userMessage.messageId = payload.user_message_id
          applyRejection(assistantMessage, payload)
        },
        onToken: (payload) => {
          applyToken(assistantMessage, payload.delta, payload.is_general_supplement)
          void scrollToBottom()
        },
        onDone: (payload: DonePayload) => {
          userMessage.messageId = payload.user_message_id
          applyDone(assistantMessage, payload)
          doneReceived = true
          terminalReceived = true
        },
        onErrorEvent: (payload) => {
          terminalReceived = true
          streamError.value = `${payload.code}: ${payload.message}`
          applyInterrupted(assistantMessage, payload.message)
        },
        onClosed: () => {
          if (!doneReceived && !terminalReceived && !controller.signal.aborted) {
            applyInterrupted(assistantMessage, '连接已结束，回答未完整生成。')
          }
        },
      },
      controller.signal,
    )
  } catch (error) {
    if (controller.signal.aborted) {
      applyInterrupted(assistantMessage, '已停止生成。')
    } else {
      const message = error instanceof ApiError
        ? `${error.code}: ${error.message}`
        : error instanceof Error ? error.message : '问答请求失败。'
      streamError.value = message
      applyInterrupted(assistantMessage, message)
      if (!(error instanceof ApiError)) {
        notifyError(error)
      }
    }
  } finally {
    streaming.value = false
    streamController = null
    if (doneReceived && selectedCourseId.value === courseId) {
      // done 后以 MySQL 权威记录刷新，确保引用/ID 与后端一致
      await loadHistory(true)
    } else {
      await scrollToBottom()
    }
  }
}

function stopStreaming(): void {
  if (!streaming.value || streamController === null) {
    return
  }
  const target = findStreamingAssistant()
  if (target !== null) {
    applyInterrupted(target, '已停止生成。')
  }
  streamController.abort()
}

function findStreamingAssistant(): UiMessage | null {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    const message = messages.value[index]
    if (message !== undefined && message.role === 'ASSISTANT' && message.streaming) {
      return message
    }
  }
  return null
}

async function clearHistory(): Promise<void> {
  const courseId = selectedCourseId.value
  if (courseId === null) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '确定清空当前课程的全部会话记录？该操作会同时清除 Redis 短期窗口，且不可恢复。',
      '清空会话',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  stopStreaming()
  try {
    const result = await clearMessages(courseId)
    messages.value = []
    nextCursor.value = null
    hasMore.value = false
    streamError.value = ''
    notifySuccess(`已清空 ${result.deleted_count} 条会话记录`)
  } catch (error) {
    notifyError(error)
  }
}

function newClientMessageId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi !== undefined && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `msg_${Date.now()}_${Math.random().toString(16).slice(2)}`
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const container = messagesEl.value
  if (container !== null) {
    container.scrollTop = container.scrollHeight
  }
}

function citationKey(citation: RagCitation): string {
  return `${citation.parent_chunk_id}-${citation.page_start}`
}

function messageMeta(message: UiMessage): string {
  if (message.role === 'USER') {
    return ''
  }
  const parts: string[] = []
  if (message.isRejected) {
    parts.push('无资料拒答')
  }
  const retrieval = formatDuration(message.retrievalCostMs)
  if (retrieval !== null) {
    parts.push(`检索 ${retrieval}`)
  }
  const firstToken = formatDuration(message.llmFirstTokenMs)
  if (firstToken !== null) {
    parts.push(`首字 ${firstToken}`)
  }
  if (message.traceId !== null) {
    parts.push(message.traceId)
  }
  return parts.join(' · ')
}
</script>

<template>
  <div class="sc-rag">
    <aside class="sc-rag__sidebar">
      <div class="sc-rag__side-block">
        <label for="rag-course">课程空间</label>
        <el-select
          id="rag-course"
          v-model="selectedCourseId"
          :loading="loadingCourses"
          placeholder="选择课程"
          class="sc-rag__course-select"
          @change="handleCourseChange"
        >
          <el-option
            v-for="course in courses"
            :key="course.course_id"
            :label="course.course_name"
            :value="course.course_id"
          />
        </el-select>
        <p v-if="courseError !== ''" class="sc-rag__muted sc-rag__error-text">{{ courseError }}</p>
      </div>

      <div v-if="selectedCourse !== null" class="sc-rag__side-block sc-rag__course-meta">
        <div>
          <span>索引版本</span>
          <strong>v{{ selectedCourse.index_version }}</strong>
        </div>
        <div>
          <span>就绪课件</span>
          <strong>{{ selectedCourse.ready_doc_count }}</strong>
        </div>
        <div>
          <span>解析中</span>
          <strong>{{ selectedCourse.parsing_doc_count }}</strong>
        </div>
        <div>
          <span>失败</span>
          <strong>{{ selectedCourse.failed_doc_count }}</strong>
        </div>
      </div>

      <el-alert
        v-if="selectedCourse?.index_status === 'REBUILDING'"
        title="课程正在重建索引；若无 READY 课件，问答会被拒绝。"
        type="warning"
        :closable="false"
        show-icon
      />

      <div class="sc-rag__side-actions">
        <el-button
          type="danger"
          plain
          :disabled="selectedCourseId === null || loadingHistory"
          @click="onClearClick"
        >
          清空当前课程会话
        </el-button>
        <router-link class="sc-rag__link" :to="{ name: 'knowledge' }">
          管理课程与课件 →
        </router-link>
      </div>
    </aside>

    <section class="sc-rag__main">
      <header class="sc-rag__hero">
        <div>
          <span class="sc-rag__eyebrow">COURSE RAG</span>
          <h2>{{ selectedCourse?.course_name ?? '课程问答' }}</h2>
          <p>回答只基于当前课程已就绪的课件，并校验真实文件名与页码；无依据时会诚实声明。</p>
        </div>
        <el-tag v-if="selectedCourse !== null" type="info" effect="plain">
          {{ selectedCourse.embedding_model }} · {{ selectedCourse.embedding_dim }} 维
        </el-tag>
      </header>

      <div ref="messagesEl" class="sc-rag__messages">
        <div v-if="loadingCourses" class="sc-rag__state">正在加载课程…</div>
        <div v-else-if="courses.length === 0" class="sc-rag__state">
          还没有课程空间。请先到
          <router-link :to="{ name: 'courses' }">课程空间</router-link>
          创建课程并上传课件。
        </div>
        <div v-else-if="loadingHistory && messages.length === 0" class="sc-rag__state">
          正在加载会话历史…
        </div>
        <div v-else-if="historyError !== '' && messages.length === 0" class="sc-rag__state sc-rag__state--error">
          {{ historyError }}
          <el-button link type="primary" @click="retryHistory">重试</el-button>
        </div>
        <div v-else-if="messages.length === 0" class="sc-rag__state">
          当前课程还没有对话。试试问一个与课件相关的问题。
        </div>

        <el-button
          v-if="hasMore"
          class="sc-rag__older"
          text
          type="primary"
          :loading="loadingHistory"
          @click="loadOlder"
        >
          加载更早消息
        </el-button>

        <article
          v-for="message in messages"
          :key="message.key"
          class="sc-rag__message"
          :class="message.role === 'USER' ? 'sc-rag__message--user' : 'sc-rag__message--assistant'"
        >
          <div class="sc-rag__bubble">
            <div v-if="message.noticeText !== null" class="sc-rag__notice">
              {{ message.noticeText }}
            </div>
            <div v-if="message.content !== ''" class="sc-rag__content">{{ message.content }}</div>
            <div v-if="message.supplement !== ''" class="sc-rag__supplement">
              <strong>通用知识补充（非本校课件原文）</strong>
              <p>{{ message.supplement }}</p>
            </div>
            <div v-if="message.errorText !== null" class="sc-rag__interrupted">
              {{ message.errorText }}
            </div>
            <div v-if="message.streaming" class="sc-rag__streaming">正在生成…</div>

            <div v-if="message.citations !== null && message.citations.length > 0" class="sc-rag__citations">
              <div class="sc-rag__citations-title">引用课件片段</div>
              <details
                v-for="citation in message.citations"
                :key="citationKey(citation)"
                class="sc-rag__citation"
              >
                <summary>
                  <span class="sc-rag__citation-name">{{ citation.doc_name }}</span>
                  <span class="sc-rag__citation-page">{{ citationPageLabel(citation) }}</span>
                  <span v-if="citationScoreLabel(citation) !== null" class="sc-rag__citation-score">
                    {{ citationScoreLabel(citation) }}
                  </span>
                </summary>
                <div class="sc-rag__citation-body">
                  <p v-if="citation.section_title" class="sc-rag__citation-section">
                    {{ citation.section_title }}
                  </p>
                  <p>{{ citation.content }}</p>
                </div>
              </details>
            </div>

            <div v-if="!message.streaming && messageMeta(message) !== ''" class="sc-rag__meta">
              {{ messageMeta(message) }}
            </div>
          </div>
        </article>
      </div>

      <div v-if="streamError !== ''" class="sc-rag__stream-error" role="alert">
        {{ streamError }}
      </div>

      <form class="sc-rag__composer" @submit.prevent="submitQuery">
        <el-input
          v-model="query"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          maxlength="2000"
          show-word-limit
          resize="none"
          placeholder="输入与当前课程课件相关的问题…（Enter 发送，Shift+Enter 换行）"
          :disabled="selectedCourseId === null || streaming"
          @keydown.enter.exact.prevent="submitQuery"
        />
        <div class="sc-rag__composer-actions">
          <span class="sc-rag__hint">回答会标注课件页码；无依据时会明确拒答。</span>
          <el-button
            v-if="streaming"
            type="danger"
            plain
            @click="stopStreaming"
          >
            停止生成
          </el-button>
          <el-button
            v-else
            type="primary"
            native-type="submit"
            :disabled="!canSend"
          >
            发送
          </el-button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.sc-rag {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--sc-page-gap);
  min-height: calc(100vh - 190px);
}

.sc-rag__sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  box-shadow: var(--sc-shadow-soft);
  align-self: start;
}

.sc-rag__side-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-rag__side-block label {
  font-size: var(--sc-text-caption);
  color: var(--sc-muted);
}

.sc-rag__course-select {
  width: 100%;
}

.sc-rag__course-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 12px;
  background: #f8faff;
  border-radius: var(--sc-radius-sm);
}

.sc-rag__course-meta div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sc-rag__course-meta span {
  font-size: var(--sc-text-caption);
  color: var(--sc-muted);
}

.sc-rag__side-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: auto;
}

.sc-rag__link {
  color: var(--sc-brand-strong);
  font-size: var(--sc-text-caption);
}

.sc-rag__main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  box-shadow: var(--sc-shadow-soft);
  overflow: hidden;
}

.sc-rag__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sc-border);
  background: linear-gradient(135deg, #f8faff 0%, #eef4ff 100%);
}

.sc-rag__eyebrow {
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--sc-brand-strong);
  font-weight: 600;
}

.sc-rag__hero h2 {
  margin: 4px 0;
  font-size: var(--sc-text-title);
  color: var(--sc-text);
}

.sc-rag__hero p {
  margin: 0;
  color: var(--sc-muted);
  font-size: var(--sc-text-caption);
}

.sc-rag__messages {
  flex: 1;
  min-height: 280px;
  max-height: calc(100vh - 420px);
  overflow-y: auto;
  padding: 18px 22px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #fbfdff;
}

.sc-rag__state {
  margin: auto;
  color: var(--sc-muted);
  text-align: center;
  line-height: 1.8;
}

.sc-rag__state--error {
  color: var(--sc-danger);
}

.sc-rag__older {
  align-self: center;
}

.sc-rag__message {
  display: flex;
}

.sc-rag__message--user {
  justify-content: flex-end;
}

.sc-rag__message--assistant {
  justify-content: flex-start;
}

.sc-rag__bubble {
  max-width: min(760px, 88%);
  padding: 12px 16px;
  border-radius: 14px;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  box-shadow: var(--sc-shadow-soft);
}

.sc-rag__message--user .sc-rag__bubble {
  background: var(--sc-brand-soft);
  border-color: #cfe0ff;
}

.sc-rag__content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.75;
}

.sc-rag__notice {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-left: 3px solid var(--sc-warning);
  background: #fffbeb;
  color: #92400e;
  border-radius: 6px;
  line-height: 1.6;
}

.sc-rag__supplement {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px dashed #c4b5fd;
  background: #f5f3ff;
  border-radius: 8px;
}

.sc-rag__supplement strong {
  display: block;
  margin-bottom: 4px;
  color: #6d28d9;
  font-size: var(--sc-text-caption);
}

.sc-rag__supplement p {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.sc-rag__interrupted {
  margin-top: 8px;
  color: var(--sc-danger);
  font-size: var(--sc-text-caption);
}

.sc-rag__streaming {
  margin-top: 6px;
  color: var(--sc-brand-strong);
  font-size: var(--sc-text-caption);
}

.sc-rag__citations {
  margin-top: 12px;
  border-top: 1px solid var(--sc-border);
  padding-top: 10px;
}

.sc-rag__citations-title {
  font-size: var(--sc-text-caption);
  color: var(--sc-muted);
  margin-bottom: 6px;
}

.sc-rag__citation {
  border: 1px solid var(--sc-border);
  border-radius: 8px;
  padding: 6px 10px;
  margin-bottom: 6px;
  background: #fff;
}

.sc-rag__citation summary {
  cursor: pointer;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-size: var(--sc-text-caption);
}

.sc-rag__citation-name {
  font-weight: 600;
  color: var(--sc-text);
}

.sc-rag__citation-page {
  color: var(--sc-brand-strong);
}

.sc-rag__citation-score {
  color: var(--sc-muted);
}

.sc-rag__citation-body {
  margin-top: 8px;
  color: var(--sc-text);
  font-size: var(--sc-text-caption);
  line-height: 1.7;
  white-space: pre-wrap;
}

.sc-rag__citation-section {
  color: var(--sc-muted);
  font-weight: 600;
}

.sc-rag__meta {
  margin-top: 10px;
  color: var(--sc-muted);
  font-size: 11px;
}

.sc-rag__stream-error {
  padding: 10px 22px;
  background: #fef2f2;
  color: var(--sc-danger);
  border-top: 1px solid #fecaca;
  font-size: var(--sc-text-caption);
}

.sc-rag__composer {
  border-top: 1px solid var(--sc-border);
  padding: 14px 22px 18px;
  background: var(--sc-surface);
}

.sc-rag__composer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.sc-rag__hint {
  color: var(--sc-muted);
  font-size: var(--sc-text-caption);
}

.sc-rag__muted {
  color: var(--sc-muted);
  font-size: var(--sc-text-caption);
}

.sc-rag__error-text {
  color: var(--sc-danger);
}

@media (max-width: 960px) {
  .sc-rag {
    grid-template-columns: 1fr;
  }

  .sc-rag__messages {
    max-height: none;
  }
}
</style>
