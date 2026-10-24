import { apiRequest, apiStream } from '@/api/client'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/error'
import type { BigIntId } from '@/api/types'

/**
 * RAG 问答与会话历史 API（`04` §4.5 `API-RAG-001` / `API-CHAT-001/002`）。
 *
 * SSE 使用 `fetch + ReadableStream`，因为浏览器 `EventSource` 不支持 `Authorization` 头。
 */

export interface RagCitation {
  parent_chunk_id: string
  doc_id: BigIntId
  doc_name: string
  page_start: number
  page_end: number
  section_title: string | null
  content: string
  similarity_score?: number | null
}

export interface CitationPayload {
  user_message_id: string
  course_index_status: string
  rebuild_progress_note: string | null
  citations: RagCitation[]
}

export interface RejectionNoticePayload {
  user_message_id: string
  notice_text: string
  has_general_supplement: boolean
}

export interface TokenPayload {
  delta: string
  is_general_supplement: boolean
}

export interface DonePayload {
  user_message_id: string
  assistant_message_id: string
  is_rejected: boolean
  retrieval_cost_ms: number
  llm_first_token_ms: number
  stripped_citation_count: number
  trace_id: string
}

export interface RagErrorPayload {
  code: string
  message: string
  trace_id: string
}

export interface RagStreamHandlers {
  onCitation?: (payload: CitationPayload) => void
  onRejectionNotice?: (payload: RejectionNoticePayload) => void
  onToken?: (payload: TokenPayload) => void
  onDone?: (payload: DonePayload) => void
  onErrorEvent?: (payload: RagErrorPayload) => void
  /** 流自然结束（不论是否收到 done）；断流由调用方根据是否收到 done 判断。 */
  onClosed?: () => void
}

export interface RagStreamRequest {
  courseId: BigIntId
  query: string
  clientMsgId?: string
}

export interface ChatMessage {
  message_id: BigIntId
  course_id: BigIntId | null
  platform: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  intent_type: string | null
  content: string
  citations: RagCitation[] | null
  is_rejected: boolean
  trace_id: string | null
  retrieval_cost_ms: number | null
  llm_first_token_ms: number | null
  created_at: string
}

export interface ChatMessagePage {
  items: ChatMessage[]
  next_cursor: string | null
  has_more: boolean
}

export interface ClearMessagesResult {
  course_id: BigIntId
  deleted_count: number
}

export interface ParsedSseFrame {
  event: string
  data: string
}

/** 解析一个 SSE 帧块；无 data 返回 null。 */
export function parseSseFrameBlock(raw: string): ParsedSseFrame | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      continue
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}

/** 增量 SSE 解析器：正确处理跨 chunk 的帧与 CRLF。 */
export function createSseFrameParser(onFrame: (frame: ParsedSseFrame) => void): {
  push: (chunk: string) => void
  flush: () => void
} {
  let buffer = ''
  const emit = (raw: string): void => {
    const frame = parseSseFrameBlock(raw)
    if (frame !== null) {
      onFrame(frame)
    }
  }
  return {
    push(chunk: string): void {
      buffer += chunk.replace(/\r\n/g, '\n')
      let index = buffer.indexOf('\n\n')
      while (index !== -1) {
        emit(buffer.slice(0, index))
        buffer = buffer.slice(index + 2)
        index = buffer.indexOf('\n\n')
      }
    },
    flush(): void {
      if (buffer.trim() !== '') {
        emit(buffer)
      }
      buffer = ''
    },
  }
}

/**
 * 发起 RAG SSE 问答并逐帧回调。
 *
 * @throws ApiError 建流失败或帧 JSON 非法；`AbortSignal` 取消时抛 `DOMException`。
 */
export async function streamRagQa(
  request: RagStreamRequest,
  handlers: RagStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await apiStream('/v1/rag/qa/stream', {
    method: 'POST',
    body: {
      course_id: request.courseId,
      query: request.query,
      client_msg_id: request.clientMsgId ?? null,
    },
    signal,
  })
  const body = response.body
  if (body === null) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE,
      message: '服务端未返回可读取的流。',
      httpStatus: response.status,
    })
  }

  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = createSseFrameParser((frame) => {
    dispatchFrame(frame, handlers)
  })
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.flush()
    handlers.onClosed?.()
  } finally {
    reader.releaseLock()
  }
}

function dispatchFrame(frame: ParsedSseFrame, handlers: RagStreamHandlers): void {
  let payload: unknown
  try {
    payload = JSON.parse(frame.data)
  } catch (error) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.MALFORMED_RESPONSE,
      message: '流式事件不是合法 JSON，已停止处理。',
      cause: error,
    })
  }
  switch (frame.event) {
    case 'citation':
      handlers.onCitation?.(payload as CitationPayload)
      break
    case 'rejection_notice':
      handlers.onRejectionNotice?.(payload as RejectionNoticePayload)
      break
    case 'token':
      handlers.onToken?.(payload as TokenPayload)
      break
    case 'done':
      handlers.onDone?.(payload as DonePayload)
      break
    case 'error':
      handlers.onErrorEvent?.(payload as RagErrorPayload)
      break
    default:
      break
  }
}

export function listMessages(
  courseId: BigIntId,
  beforeMessageId?: BigIntId | null,
  limit = 20,
): Promise<ChatMessagePage> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (beforeMessageId !== undefined && beforeMessageId !== null) {
    params.set('before_message_id', beforeMessageId)
  }
  return apiRequest<ChatMessagePage>(
    `/v1/courses/${encodeURIComponent(courseId)}/messages?${params.toString()}`,
  )
}

export function clearMessages(courseId: BigIntId): Promise<ClearMessagesResult> {
  return apiRequest<ClearMessagesResult>(
    `/v1/courses/${encodeURIComponent(courseId)}/messages`,
    { method: 'DELETE' },
  )
}
