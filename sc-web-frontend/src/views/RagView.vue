<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { Component } from 'vue'

import {
  BookOpen,
  ClipboardList,
  Code2,
  FileText,
  Globe,
  MessageSquare,
  Sparkles,
  Trash2,
  Upload,
} from '@lucide/vue'
import { ElMessageBox } from 'element-plus'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { ApiError } from '@/api/error'
import { clearMessages, listMessages, streamRagQa } from '@/api/rag'
import type {
  ChatMessage,
  CitationPayload,
  DonePayload,
  RagCitation,
} from '@/api/rag'
import BaseCard from '@/components/ui/BaseCard.vue'
import ComingSoonBadge from '@/components/ui/ComingSoonBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useCourseStore } from '@/stores/course'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import {
  recentChatPairs,
  relativeTimeLabel,
  type ChatPair,
} from '@/views/home/dashboard-data'
import {
  applyDone,
  applyInterrupted,
  applyRejection,
  applyStreamFailure,
  applyToken,
  citationPageLabel,
  citationScoreLabel,
  createLocalMessage,
  formatDuration,
  historyToUiMessages,
  queryError,
  splitInlineCitations,
  type InlineSegment,
  type UiMessage,
} from '@/views/rag/rag-chat'

/**
 * RAG 溯源问答工作台（`06` TASK-605、`04` §4.5）。
 *
 * 只展示后端真实返回的引用与状态：引用卡片来自 `citation` 事件/会话历史，
 * 断流或错误会明确标为“未完成”，不会把半截回答当作成功。
 */

const courseStore = useCourseStore()
const auth = useAuthStore()

const PROMPT_SUGGESTIONS = [
  '请基于我的知识库，解释一下这个概念…',
  '帮我分析这段代码的实现思路…',
  '我有一个课设/大作业，帮我规划一下实现步骤…',
  '总结一下这份文档的重点内容…',
]
const PROMPT_ICONS: Component[] = [BookOpen, Code2, ClipboardList, FileText]

interface FocusableInput {
  focus: () => void
}

const courses = ref<CourseNamespace[]>([])
const selectedCourseId = ref<string | null>(courseStore.selectedCourseId)
const messages = ref<UiMessage[]>([])
const historyItems = ref<ChatMessage[]>([])
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
const queryInput = ref<FocusableInput | null>(null)
const nowMs = ref(Date.now())

let streamController: AbortController | null = null
let historySeq = 0
let clockTimer: number | null = null

const selectedCourse = computed(
  () => courses.value.find((course) => course.course_id === selectedCourseId.value) ?? null,
)
const canSend = computed(
  () => selectedCourseId.value !== null && !streaming.value && query.value.trim() !== '',
)
const recentPairs = computed<ChatPair[]>(() => recentChatPairs(historyItems.value, 4))

onMounted(() => {
  void bootstrap()
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onUnmounted(() => {
  streamController?.abort()
  if (clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
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
  historyItems.value = []
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
    historyItems.value = reset ? page.items : [...page.items, ...historyItems.value]
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
          if (doneReceived) {
            // done 已落库成功；忽略传输尾部错误，避免成功回答被误判为失败
            return
          }
          terminalReceived = true
          streamError.value = `${payload.code}: ${payload.message}`
          applyStreamFailure(assistantMessage, payload.message)
        },
        onClosed: () => {
          if (!doneReceived && !terminalReceived && !controller.signal.aborted) {
            streamError.value = '连接已结束，回答未完整生成。'
            applyStreamFailure(assistantMessage, '连接已结束，回答未完整生成。')
          }
        },
      },
      controller.signal,
    )
  } catch (error) {
    if (doneReceived) {
      // 已收到 done 并完成落库：忽略后续传输层异常
    } else if (controller.signal.aborted) {
      applyInterrupted(assistantMessage, '已停止生成。')
    } else {
      const message = error instanceof ApiError
        ? `${error.code}: ${error.message}`
        : error instanceof Error ? error.message : '问答请求失败。'
      streamError.value = message
      applyStreamFailure(assistantMessage, message)
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
    historyItems.value = []
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

function inlineSegments(message: UiMessage): InlineSegment[] {
  return splitInlineCitations(message.content, message.citations)
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

function applyPrompt(prompt: string): void {
  query.value = prompt
  void nextTick(() => {
    queryInput.value?.focus()
  })
}

function focusComposer(): void {
  void nextTick(() => {
    queryInput.value?.focus()
  })
}

function jumpToPair(pair: ChatPair): void {
  const messageId = pair.assistant.message_id
  const target = document.getElementById(`rag-msg-${messageId}`)
  if (target !== null) {
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    return
  }
  applyPrompt(pair.user.content)
}

function relativeTime(value: string): string {
  return relativeTimeLabel(value, nowMs.value)
}
</script>

<template>
  <div class="rag-page">
    <section class="rag-main">
      <header class="rag-header">
        <div class="rag-header__copy">
          <span class="rag-header__eyebrow">AI LEARNING</span>
          <h2>{{ selectedCourse?.course_name ?? 'AI 学习' }}</h2>
          <p>基于你的课程知识库与真实引用，提供学习问答、代码解析与学习规划。</p>
        </div>
        <div class="rag-header__controls">
          <el-select
            v-model="selectedCourseId"
            :loading="loadingCourses"
            placeholder="选择课程"
            class="rag-course-select"
            @change="handleCourseChange"
          >
            <el-option
              v-for="course in courses"
              :key="course.course_id"
              :label="course.course_name"
              :value="course.course_id"
            />
          </el-select>
          <TagBadge
            v-if="selectedCourse !== null"
            :label="`${selectedCourse.embedding_model} · ${selectedCourse.embedding_dim} 维`"
            tone="primary"
          />
          <el-button
            plain
            type="danger"
            :icon="Trash2"
            :disabled="selectedCourseId === null || loadingHistory"
            @click="onClearClick"
          >
            清空会话
          </el-button>
        </div>
        <p v-if="courseError !== ''" class="rag-inline-error">{{ courseError }}</p>
        <el-alert
          v-if="selectedCourse?.index_status === 'REBUILDING'"
          class="rag-rebuild-alert"
          title="课程正在重建索引；若无 READY 课件，问答会被拒绝。"
          type="warning"
          :closable="false"
          show-icon
        />
      </header>

      <section class="rag-panel">
        <div ref="messagesEl" class="rag-messages">
          <div v-if="loadingCourses" class="rag-state">正在加载课程…</div>

          <EmptyState
            v-else-if="courses.length === 0"
            image="/illustrations/empty-cat-reading.png"
            title="还没有课程空间"
            description="先到课程空间创建课程并上传课件，再开始提问。"
            compact
          >
            <template #action>
              <router-link class="rag-link" :to="{ name: 'courses' }">去课程空间</router-link>
            </template>
          </EmptyState>

          <div v-else-if="loadingHistory && messages.length === 0" class="rag-state">
            正在加载会话历史…
          </div>

          <div
            v-else-if="historyError !== '' && messages.length === 0"
            class="rag-state rag-state--error"
          >
            {{ historyError }}
            <el-button link type="primary" @click="retryHistory">重试</el-button>
          </div>

          <div v-else-if="messages.length === 0" class="rag-welcome">
            <div class="rag-welcome__avatar">
              <img src="/illustrations/ai-avatar-main.png" alt="" />
            </div>
            <h3>你好，{{ auth.displayName }}</h3>
            <p>
              我可以帮你解答课程知识、分析代码、规划课设/大作业，也可以基于你的知识库为你提供精准的参考和建议。
            </p>
            <div class="rag-welcome__prompts">
              <button
                v-for="(prompt, index) in PROMPT_SUGGESTIONS"
                :key="prompt"
                type="button"
                class="rag-prompt"
                @click="applyPrompt(prompt)"
              >
                <component
                  :is="PROMPT_ICONS[index] ?? BookOpen"
                  :size="14"
                  :stroke-width="1.7"
                />
                <span>{{ prompt }}</span>
              </button>
            </div>
            <div class="rag-welcome__art">
              <img src="/illustrations/empty-cat-reading.png" alt="" />
            </div>
          </div>

          <template v-else>
            <el-button
              v-if="hasMore"
              class="rag-older"
              text
              type="primary"
              :loading="loadingHistory"
              @click="loadOlder"
            >
              加载更早消息
            </el-button>

            <article
              v-for="message in messages"
              :id="`rag-msg-${message.messageId ?? message.key}`"
              :key="message.key"
              class="rag-message"
              :class="message.role === 'USER' ? 'rag-message--user' : 'rag-message--assistant'"
            >
              <span v-if="message.role === 'ASSISTANT'" class="rag-message__avatar">
                <img src="/illustrations/ai-avatar-main.png" alt="" />
              </span>

              <div class="rag-bubble">
                <div v-if="message.noticeText !== null" class="rag-notice">
                  {{ message.noticeText }}
                </div>

                <div v-if="message.content !== ''" class="rag-content">
                  <template
                    v-for="(segment, index) in inlineSegments(message)"
                    :key="`seg-${index}`"
                  >
                    <span v-if="segment.citationIndex === null">{{ segment.text }}</span>
                    <span v-else class="rag-inline-citation">{{ segment.text }}</span>
                  </template>
                </div>

                <div v-if="message.supplement !== ''" class="rag-supplement">
                  <strong>通用知识补充（非本校课件原文）</strong>
                  <p>{{ message.supplement }}</p>
                </div>

                <div v-if="message.errorText !== null" class="rag-interrupted">
                  {{ message.errorText }}
                </div>

                <div v-if="message.streaming" class="rag-streaming">
                  <img src="/illustrations/ai-state-thinking.png" alt="" />
                  正在生成…
                </div>

                <div
                  v-if="message.citations !== null && message.citations.length > 0"
                  class="rag-citations"
                >
                  <div class="rag-citations-title">引用课件片段</div>
                  <details
                    v-for="citation in message.citations"
                    :key="citationKey(citation)"
                    class="rag-citation"
                  >
                    <summary>
                      <span class="rag-citation-name">{{ citation.doc_name }}</span>
                      <span class="rag-citation-page">{{ citationPageLabel(citation) }}</span>
                      <span
                        v-if="citationScoreLabel(citation) !== null"
                        class="rag-citation-score"
                      >
                        {{ citationScoreLabel(citation) }}
                      </span>
                    </summary>
                    <div class="rag-citation-body">
                      <p v-if="citation.section_title" class="rag-citation-section">
                        {{ citation.section_title }}
                      </p>
                      <p>{{ citation.content }}</p>
                    </div>
                  </details>
                </div>

                <div v-if="!message.streaming && messageMeta(message) !== ''" class="rag-meta">
                  {{ messageMeta(message) }}
                </div>
              </div>
            </article>
          </template>
        </div>

        <div v-if="streamError !== ''" class="rag-stream-error" role="alert">
          {{ streamError }}
        </div>

        <form class="rag-composer" @submit.prevent="submitQuery">
          <el-input
            ref="queryInput"
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

          <div class="rag-composer__bar">
            <div class="rag-composer__tools">
              <span class="rag-tool">
                <BookOpen :size="14" :stroke-width="1.7" />
                {{ selectedCourse?.course_name ?? '未选择课程' }}
              </span>
              <router-link class="rag-tool" :to="{ name: 'knowledge' }">
                <Upload :size="14" :stroke-width="1.7" />
                上传文件
              </router-link>
              <el-tooltip content="联网搜索暂未开发" placement="top">
                <span class="rag-tool rag-tool--disabled">
                  <Globe :size="14" :stroke-width="1.7" />
                  联网搜索
                  <ComingSoonBadge compact />
                </span>
              </el-tooltip>
            </div>

            <div class="rag-composer__send">
              <span class="rag-composer__hint">回答会标注课件页码；无依据时会明确拒答。</span>
              <el-button v-if="streaming" type="danger" plain @click="stopStreaming">
                停止生成
              </el-button>
              <el-button v-else type="primary" native-type="submit" :disabled="!canSend">
                发送
              </el-button>
            </div>
          </div>
        </form>
      </section>
    </section>

    <aside class="rag-rail">
      <BaseCard title="快捷入口">
        <div class="rag-rail__entries">
          <button type="button" class="rag-entry" @click="focusComposer">
            <span class="rag-entry__icon">
              <BookOpen :size="17" :stroke-width="1.7" />
            </span>
            <span class="rag-entry__text">
              <strong>基于知识库问答</strong>
              <small>选择课程文档，获取精准答案</small>
            </span>
          </button>
          <div class="rag-entry rag-entry--disabled">
            <span class="rag-entry__icon">
              <Sparkles :size="17" :stroke-width="1.7" />
            </span>
            <span class="rag-entry__text">
              <strong>课设 / 大作业助手</strong>
              <small>需求分析 · 方案设计 · 代码实现</small>
            </span>
            <ComingSoonBadge compact />
          </div>
        </div>
      </BaseCard>

      <BaseCard title="最近对话">
        <EmptyState
          v-if="recentPairs.length === 0"
          image="/illustrations/empty-cat-reading.png"
          title="还没有对话"
          description="向 AI 提问后，最近的问答会显示在这里。"
          compact
        />
        <ul v-else class="rag-recent">
          <li v-for="pair in recentPairs" :key="pair.assistant.message_id">
            <button type="button" class="rag-recent__item" @click="jumpToPair(pair)">
              <span class="rag-recent__icon">
                <MessageSquare :size="14" :stroke-width="1.7" />
              </span>
              <span class="rag-recent__text">
                <strong>{{ pair.user.content }}</strong>
                <small>{{ relativeTime(pair.assistant.created_at) }}</small>
              </span>
            </button>
          </li>
        </ul>
      </BaseCard>

      <div class="rag-quote">
        <img src="/illustrations/scene-study-desk.png" alt="" />
        <p>学习不是一场冲刺<br />而是一段持续积累的旅程</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.rag-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--sc-card-gap);
  height: calc(100dvh - var(--sc-topbar-height) - 52px);
  min-height: 620px;
}

.rag-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
}

.rag-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--sc-border);
  border-radius: 16px;
  background: linear-gradient(
    120deg,
    var(--sc-primary-soft) 0%,
    var(--sc-surface) 58%,
    var(--sc-accent-soft) 100%
  );
  box-shadow: var(--sc-shadow);
}

.rag-header__copy {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.rag-header__eyebrow {
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
  font-weight: 700;
  letter-spacing: 1.2px;
}

.rag-header h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
}

.rag-header p {
  max-width: 560px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.rag-header__controls {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.rag-course-select {
  width: 220px;
}

.rag-inline-error {
  flex-basis: 100%;
  color: var(--sc-danger);
  font-size: var(--sc-font-hint);
}

.rag-rebuild-alert {
  flex-basis: 100%;
}

.rag-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--sc-border);
  border-radius: 16px;
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.rag-messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 18px 20px;
  overflow-y: auto;
  background: var(--sc-surface-muted);
}

.rag-state {
  margin: auto;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.8;
  text-align: center;
}

.rag-state--error {
  color: var(--sc-danger);
}

.rag-older {
  align-self: center;
}

.rag-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  width: 100%;
  max-width: 660px;
  margin: auto;
  padding: 16px;
  text-align: center;
}

.rag-welcome__avatar {
  display: grid;
  place-items: center;
  width: 84px;
  height: 84px;
  border-radius: 24px;
  background: #f1f6ff;
}

.rag-welcome__avatar img {
  width: 72px;
  height: auto;
}

.rag-welcome h3 {
  color: var(--sc-text);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.2px;
}

.rag-welcome p {
  max-width: 540px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.8;
}

.rag-welcome__prompts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
  margin-top: 8px;
}

.rag-prompt {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--sc-border);
  border-radius: 10px;
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-hint);
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease, background-color 0.18s ease;
}

.rag-prompt:hover,
.rag-prompt:focus-visible {
  border-color: var(--sc-primary);
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  outline: none;
}

.rag-prompt svg {
  flex: none;
  margin-top: 1px;
}

.rag-welcome__art {
  width: 220px;
  max-width: 60%;
  margin-top: 4px;
  padding: 8px;
  border-radius: var(--sc-radius-card);
  background: #f1f6ff;
}

.rag-welcome__art img {
  display: block;
  width: 100%;
  max-height: 110px;
  object-fit: contain;
}

.rag-message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.rag-message--user {
  justify-content: flex-end;
}

.rag-message--assistant {
  justify-content: flex-start;
}

.rag-message__avatar {
  display: grid;
  flex: none;
  place-items: center;
  width: 30px;
  height: 30px;
  overflow: hidden;
  border-radius: 50%;
  background: #eef4ff;
}

.rag-message__avatar img {
  width: 26px;
  height: 26px;
  object-fit: contain;
}

.rag-bubble {
  max-width: min(760px, 80%);
  padding: 12px 15px;
  border: 1px solid var(--sc-border);
  border-radius: 14px;
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.rag-message--user .rag-bubble {
  border-color: transparent;
  background: var(--sc-primary-soft);
  box-shadow: none;
}

.rag-notice {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #b45309;
  background: var(--sc-warning-soft);
  font-size: var(--sc-font-caption);
  line-height: 1.6;
}

.rag-content {
  color: var(--sc-text);
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-inline-citation {
  padding: 0 2px;
  border-radius: 4px;
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
}

.rag-supplement {
  margin-top: 8px;
  padding: 9px 11px;
  border-radius: 8px;
  background: var(--sc-accent-soft);
}

.rag-supplement strong {
  color: var(--sc-accent);
  font-size: var(--sc-font-hint);
}

.rag-supplement p {
  margin-top: 3px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
  white-space: pre-wrap;
}

.rag-interrupted {
  margin-top: 8px;
  color: var(--sc-danger);
  font-size: var(--sc-font-caption);
}

.rag-streaming {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--sc-primary);
  font-size: var(--sc-font-hint);
}

.rag-streaming img {
  width: 22px;
  height: 22px;
  object-fit: contain;
}

.rag-citations {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}

.rag-citations-title {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.rag-citation {
  padding: 8px 10px;
  border: 1px solid var(--sc-border);
  border-radius: 10px;
  background: var(--sc-surface-muted);
}

.rag-citation summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--sc-font-caption);
  list-style: none;
  cursor: pointer;
}

.rag-citation summary::-webkit-details-marker {
  display: none;
}

.rag-citation-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--sc-text);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rag-citation-page,
.rag-citation-score {
  flex: none;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.rag-citation-body {
  margin-top: 6px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
  line-height: 1.7;
  white-space: pre-wrap;
}

.rag-citation-section {
  margin-bottom: 4px;
  color: var(--sc-primary-strong);
  font-weight: 600;
}

.rag-meta {
  margin-top: 8px;
  color: var(--sc-text-placeholder);
  font-size: 10px;
}

.rag-stream-error {
  padding: 9px 16px;
  border-top: 1px solid var(--sc-border);
  color: var(--sc-danger);
  background: var(--sc-danger-soft);
  font-size: var(--sc-font-hint);
}

.rag-composer {
  padding: 12px 16px 14px;
  border-top: 1px solid var(--sc-border);
  background: var(--sc-surface);
}

.rag-composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.rag-composer__tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.rag-tool {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
  text-decoration: none;
}

.rag-tool--disabled {
  color: var(--sc-text-placeholder);
  cursor: not-allowed;
}

.rag-composer__send {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rag-composer__hint {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.rag-rail {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  min-width: 0;
  overflow-y: auto;
}

.rag-rail__entries {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rag-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border: 0;
  border-radius: 10px;
  color: inherit;
  background: transparent;
  font-family: var(--sc-font-sans);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease;
}

.rag-entry:hover {
  background: var(--sc-surface-hover);
}

.rag-entry--disabled {
  cursor: not-allowed;
  opacity: 0.78;
}

.rag-entry--disabled:hover {
  background: transparent;
}

.rag-entry__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.rag-entry--disabled .rag-entry__icon {
  color: var(--sc-accent);
  background: var(--sc-accent-soft);
}

.rag-entry__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.rag-entry__text strong {
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
}

.rag-entry__text small {
  overflow: hidden;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rag-recent {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.rag-recent__item {
  display: flex;
  gap: 9px;
  width: 100%;
  padding: 8px;
  border: 0;
  border-radius: 10px;
  color: inherit;
  background: transparent;
  font-family: var(--sc-font-sans);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease;
}

.rag-recent__item:hover,
.rag-recent__item:focus-visible {
  background: var(--sc-surface-hover);
  outline: none;
}

.rag-recent__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--sc-accent);
  background: var(--sc-accent-soft);
}

.rag-recent__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.rag-recent__text strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rag-recent__text small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.rag-quote {
  position: relative;
  min-height: 110px;
  overflow: hidden;
  border-radius: var(--sc-radius-card);
  background: #eaf2ff;
}

.rag-quote img {
  display: block;
  width: 100%;
  height: 130px;
  object-fit: cover;
  opacity: 0.9;
}

.rag-quote p {
  position: absolute;
  left: 14px;
  bottom: 14px;
  color: #1f2937;
  font-size: var(--sc-font-caption);
  font-weight: 600;
  line-height: 1.6;
}

.rag-link {
  color: var(--sc-primary);
  font-size: var(--sc-font-caption);
  text-decoration: none;
}

@media (max-width: 1199px) {
  .rag-page {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 0;
  }

  .rag-panel {
    min-height: 620px;
  }

  .rag-rail {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
    overflow-y: visible;
  }
}

@media (max-width: 860px) {
  .rag-rail {
    grid-template-columns: 1fr;
  }

  .rag-welcome__prompts {
    grid-template-columns: 1fr;
  }

  .rag-bubble {
    max-width: 90%;
  }
}

@media (max-width: 639px) {
  .rag-header {
    padding: 16px;
  }

  .rag-header__controls {
    width: 100%;
  }

  .rag-course-select {
    flex: 1;
    width: auto;
  }

  .rag-panel {
    min-height: 560px;
  }

  .rag-messages {
    padding: 14px;
  }

  .rag-composer {
    padding: 10px 12px 12px;
  }

  .rag-composer__hint {
    display: none;
  }
}
</style>
