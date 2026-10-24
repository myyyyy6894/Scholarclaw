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

/** 内联内容片段：命中已验证引用时带 citationIndex，渲染为高亮。 */
export interface InlineSegment {
  text: string
  citationIndex: number | null
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
      // 拒答历史把声明与通用补充分开恢复，避免声明被当成普通回答正文
      const rejected = item.is_rejected
      return {
        key: `msg-${item.message_id}`,
        messageId: item.message_id,
        role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
        content: rejected ? '' : parts.main,
        supplement: parts.supplement,
        citations: item.citations,
        isRejected: rejected,
        noticeText: rejected && parts.main !== '' ? parts.main : null,
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
  // 拒答不得与任何“成功回答”内容或引用卡片同时展示
  message.content = ''
  message.supplement = ''
  message.citations = null
  message.streaming = false
}

export function applyStreamFailure(message: UiMessage, reason: string): void {
  // 错误/断流/引用校验失败：丢弃半截回答与引用，避免被误认为成功
  message.content = ''
  message.supplement = ''
  message.citations = null
  message.streaming = false
  message.interrupted = true
  message.errorText = reason
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
  if (citation.similarity_score === undefined || citation.similarity_score === null) {
    return null
  }
  return `相似度 ${citation.similarity_score.toFixed(3)}`
}

const INLINE_CITATION_PATTERN =
  /[[【]\s*来源\s*[:：]\s*(.+?)\s*[-－—–‑]\s*第\s*(\d+)\s*页\s*[\]】]/g

/**
 * 把回答正文拆成普通文本与已验证引用片段。
 *
 * 只有文件名与页码同时命中后端 `citations` 的标签才高亮；其余保持纯文本，
 * 渲染时一律文本插值（不使用 `v-html`）。
 */
export function splitInlineCitations(
  content: string,
  citations: RagCitation[] | null,
): InlineSegment[] {
  const single: InlineSegment[] = [{ text: content, citationIndex: null }]
  if (content === '' || citations === null || citations.length === 0) {
    return single
  }
  const pattern = new RegExp(INLINE_CITATION_PATTERN.source, 'g')
  const segments: InlineSegment[] = []
  let last = 0
  for (const match of content.matchAll(pattern)) {
    const docName = (match[1] ?? '').trim()
    const page = Number(match[2])
    const index = citations.findIndex(
      (citation) =>
        citation.doc_name.trim() === docName &&
        citation.page_start <= page &&
        page <= citation.page_end,
    )
    if (index === -1) {
      continue
    }
    const start = match.index ?? 0
    if (start > last) {
      segments.push({ text: content.slice(last, start), citationIndex: null })
    }
    segments.push({ text: match[0], citationIndex: index })
    last = start + match[0].length
  }
  if (last < content.length) {
    segments.push({ text: content.slice(last), citationIndex: null })
  }
  return segments.length === 0 ? single : segments
}

export function formatDuration(ms: number | null): string | null {
  return ms === null ? null : `${ms} ms`
}
