import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { configureApiClient, resetApiClient } from '@/api/client'
import { ApiError } from '@/api/error'
import {
  createSseFrameParser,
  parseSseFrameBlock,
  streamRagQa,
} from '@/api/rag'
import type { DonePayload, TokenPayload } from '@/api/rag'

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
  })
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('sse frame parsing', () => {
  it('解析 event/data 并忽略注释与心跳', () => {
    expect(parseSseFrameBlock(': ping\nevent: token\ndata: {"delta":"x"}')).toEqual({
      event: 'token',
      data: '{"delta":"x"}',
    })
    expect(parseSseFrameBlock(': ping')).toBeNull()
  })

  it('正确处理跨 chunk 与 CRLF', () => {
    const frames: Array<{ event: string; data: string }> = []
    const parser = createSseFrameParser((frame) => frames.push(frame))

    parser.push('event: token\r\ndata: {"del')
    parser.push('ta":"x"}\r\n\r\n')
    parser.push('event: done\ndata: {"ok":true}\n\n')
    parser.flush()

    expect(frames).toEqual([
      { event: 'token', data: '{"delta":"x"}' },
      { event: 'done', data: '{"ok":true}' },
    ])
  })
})

describe('streamRagQa', () => {
  beforeEach(() => {
    resetApiClient()
    configureApiClient({ tokenProvider: () => 'jwt-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('发送带鉴权的 POST 并分发五类事件', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([
      'event: citation\ndata: {"user_message_id":"90001","course_index_status":"ACTIVE",'
        + '"rebuild_progress_note":null,"citations":[]}\n\n',
      'event: token\ndata: {"delta":"答案","is_general_supplement":false}\n\n',
      'event: done\ndata: {"user_message_id":"90001","assistant_message_id":"90002",'
        + '"is_rejected":false,"retrieval_cost_ms":185,"llm_first_token_ms":1120,'
        + '"stripped_citation_count":0,"trace_id":"tr_0123456789ab"}\n\n',
    ]))
    vi.stubGlobal('fetch', fetchMock)
    const tokens: TokenPayload[] = []
    const done: DonePayload[] = []
    let closed = false

    await streamRagQa(
      { courseId: '2001', query: '问题', clientMsgId: 'c-1' },
      {
        onToken: (payload) => tokens.push(payload),
        onDone: (payload) => done.push(payload),
        onClosed: () => {
          closed = true
        },
      },
    )

    expect(tokens).toEqual([{ delta: '答案', is_general_supplement: false }])
    expect(done[0]?.assistant_message_id).toBe('90002')
    expect(closed).toBe(true)
    const request = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(request[0]).toBe('/api/v1/rag/qa/stream')
    expect(request[1].method).toBe('POST')
    expect((request[1].headers as Record<string, string>).Authorization)
      .toBe('Bearer jwt-token')
    expect(request[1].body).toBe(JSON.stringify({
      course_id: '2001', query: '问题', client_msg_id: 'c-1',
    }))
  })

  it('建流前 4xx 信封原样保留错误码', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'COURSE_NO_READY_DOCS',
      message: '该课程下暂无已就绪的课件',
      data: null,
      trace_id: 'tr_0123456789ab',
      timestamp: '2026-10-23T10:00:00.000+08:00',
    }, 400)))

    await expect(streamRagQa(
      { courseId: '2001', query: '问题' },
      {},
    )).rejects.toMatchObject({ code: 'COURSE_NO_READY_DOCS' })
  })

  it('流内非法 JSON 停止处理', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: token\ndata: not-json\n\n',
    ])))

    await expect(streamRagQa(
      { courseId: '2001', query: '问题' },
      {},
    )).rejects.toBeInstanceOf(ApiError)
  })
})
