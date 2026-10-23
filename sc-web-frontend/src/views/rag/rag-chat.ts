import type {
  ChatMessage,
  DonePayload,
  RagCitation,
  RejectionNoticePayload,
} from '@/api/rag'

/** RAG 问答页的纯展示/状态逻辑；不依赖 Vue 组件，便于单测。 */

export const SUPPLEMENT_PREFIX =
  '💡 以下为基于通用专业知识的补充参考（非本校课件原文，请注意甄别）：'
export const MAX_QUERY_LENGTH = 2000

export interface UiMessage {
  key: string
  messageId: string | null
  role: 'USER' | 'ASSISTANT'
  content: string
  supplement: string
  citations: RagCitation[] | null
  isRejected: boolean
  noticeText: string | null
  interrupted: boolean
  streaming: boolean
  errorText: string | null
  traceId: string | null
  retrievalCostMs: number | null
  llmFirstTokenMs: number | null
}

export interface SupplementParts {
  main: string
  supplement: string
}

/** 把历史记录中的回答拆成“课件依据”和“通用补充”两段，供 UI 分别渲染。 */
export function splitSupplement(content: string): SupplementParts {
  const index = content.indexOf(SUPPLEMENT_PREFIX)
  if (index === -1) {
    return { main: content, supplement: '' }
  }
  return {
    main: content.slice(0, index).trimEnd(),
    supplement: content.slice(index + SUPPLEMENT_PREFIX.length).trimStart(),
  }
}

export function historyToUiMessages(items: ChatMessage[]): UiMessage[] {
  return items
    .filter((item) => item.role === 'USER' || item.role === 'ASSISTANT')
    .map((item) => {
      const parts = splitSupplement(item.content)
      return {
        key: `msg-${item.message_id}`,
        messageId: item.message_id,
        role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
        content: parts.main,
        supplement: parts.supplement,
        citations: item.citations,
        isRejected: item.is_rejected,
        noticeText: null,
        interrupted: false,
        streaming: false,
        errorText: null,
        traceId: item.trace_id,
        retrievalCostMs: item.retrieval_cost_ms,
        llmFirstTokenMs: item.llm_first_token_ms,
      }
    })
}

export function createLocalMessage(
  role: 'USER' | 'ASSISTANT',
  content = '',
): UiMessage {
  return {
    key: `local-${role}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    messageId: null,
    role,
    content,
    supplement: '',
    citations: null,
    isRejected: false,
    noticeText: null,
    interrupted: false,
    streaming: role === 'ASSISTANT',
    errorText: null,
    traceId: null,
    retrievalCostMs: null,
    llmFirstTokenMs: null,
  }
}

export function applyToken(message: UiMessage, delta: string, general: boolean): void {
  if (general) {
    message.supplement += delta
  } else {
    message.content += delta
  }
}

export function applyRejection(
  message: UiMessage,
  payload: RejectionNoticePayload,
): void {
  message.noticeText = payload.notice_text
  message.isRejected = true
}

export function applyDone(message: UiMessage, payload: DonePayload): void {
  message.messageId = payload.assistant_message_id
  message.isRejected = payload.is_rejected
  message.traceId = payload.trace_id
  message.retrievalCostMs = payload.retrieval_cost_ms
  message.llmFirstTokenMs = payload.llm_first_token_ms
  message.streaming = false
  message.interrupted = false
}

export function applyInterrupted(message: UiMessage, reason: string): void {
  message.streaming = false
  message.interrupted = true
  message.errorText = reason
}

export function queryError(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed === '') {
    return '请输入问题。'
  }
  if (trimmed.length > MAX_QUERY_LENGTH) {
    return `问题长度不得超过 ${MAX_QUERY_LENGTH} 个字符。`
  }
  return null
}

export function citationPageLabel(citation: RagCitation): string {
  if (citation.page_start === citation.page_end) {
    return `第 ${citation.page_start} 页`
  }
  return `第 ${citation.page_start}–${citation.page_end} 页`
}

export function citationScoreLabel(citation: RagCitation): string | null {
  if (citation.similarity_score === undefined) {
    return null
  }
  return `相似度 ${citation.similarity_score.toFixed(3)}`
}

export function formatDuration(ms: number | null): string | null {
  return ms === null ? null : `${ms} ms`
}
