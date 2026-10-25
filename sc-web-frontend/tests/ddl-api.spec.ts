import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { configureApiClient, resetApiClient } from '@/api/client'
import {
  createDdlTask,
  decideDdlDraft,
  deleteDdlTask,
  extractDdlTask,
  listDdlTasks,
  setDdlTaskCompletion,
  updateDdlTask,
} from '@/api/ddl'
import { ApiError } from '@/api/error'

function envelope<T>(data: T, code = 'OK', message = 'OK') {
  return {
    code,
    message,
    data,
    trace_id: 'tr_ddltest01',
    timestamp: '2026-10-25T12:00:00+08:00',
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

interface RecordedCall {
  url: string
  init: RequestInit
  body: () => unknown
}

function lastCall(fetchMock: ReturnType<typeof vi.fn>): RecordedCall {
  const call = fetchMock.mock.calls.at(-1)
  if (call === undefined) {
    throw new Error('fetch 未被调用')
  }
  const init = (call[1] ?? {}) as RequestInit
  return {
    url: String(call[0]),
    init,
    body: () => {
      if (typeof init.body !== 'string') {
        throw new Error('请求体不是 JSON 字符串')
      }
      return JSON.parse(init.body) as unknown
    },
  }
}

describe('ddl api', () => {
  beforeEach(() => {
    resetApiClient()
    configureApiClient({ tokenProvider: () => 'jwt-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetApiClient()
  })

  it('看板查询带鉴权且按参数拼 query', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        jsonResponse(
          envelope({
            pending_inbox_tasks: [],
            pending_change_drafts: [],
            active_tasks: [],
            completed_tasks: [],
          }),
        ),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await listDdlTasks({ statusGroup: 'PENDING_INBOX', courseId: '2001' })
    let call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks?status_group=PENDING_INBOX&course_id=2001')
    expect(call.init.method).toBe('GET')
    expect((call.init.headers as Record<string, string>).Authorization).toBe('Bearer jwt-token')

    await listDdlTasks()
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks')

    await listDdlTasks({ courseId: '' })
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks')
  })

  it('通知提取提交原文、幂等 ID 与 force_create', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        envelope({
          extract_outcome: 'CREATED',
          task: { task_id: '102' },
          change_draft: null,
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await extractDdlTask({
      raw_text: '本周五下午 5 点前提交实验报告',
      client_request_id: 'req_ddl_1',
      force_create: true,
      course_id: '2002',
    })

    const call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/extract')
    expect(call.init.method).toBe('POST')
    expect(call.body()).toEqual({
      raw_text: '本周五下午 5 点前提交实验报告',
      client_request_id: 'req_ddl_1',
      force_create: true,
      course_id: '2002',
    })
    expect(result.extract_outcome).toBe('CREATED')
  })

  it('手动新建 / 编辑 / 决策 / 完成 / 删除命中正确路径与方法', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(jsonResponse(envelope({ task_id: '103' }))),
    )
    vi.stubGlobal('fetch', fetchMock)

    await createDdlTask({
      client_request_id: 'req_manual_1',
      course_name: '数据库系统概念',
      task_title: '完成第 3 次作业',
      time_precision: 'DATE_ONLY',
      due_date: '2026-10-25',
      due_time: null,
    })
    let call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks')
    expect(call.init.method).toBe('POST')

    await updateDdlTask('103', {
      course_id: null,
      course_name: '数据库系统概念',
      task_title: '完成第 3 次作业',
      time_precision: 'EXACT_DATETIME',
      due_date: '2026-10-25',
      due_time: '22:00:00',
    })
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/103')
    expect(call.init.method).toBe('PUT')
    expect((call.body() as { course_id: string | null }).course_id).toBeNull()

    await decideDdlDraft('103', {
      decision: 'CONFIRM_UPDATE',
      client_request_id: 'req_draft_1',
    })
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/103/draft-decision')
    expect(call.init.method).toBe('POST')
    expect((call.body() as { decision: string }).decision).toBe('CONFIRM_UPDATE')

    await setDdlTaskCompletion('103', true)
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/103/completion')
    expect(call.init.method).toBe('PATCH')
    expect(call.body()).toEqual({ completed: true })

    await deleteDdlTask('103')
    call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/103')
    expect(call.init.method).toBe('DELETE')
  })

  it('任务 ID 以字符串拼接路径，不做数值转换', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(envelope({ deleted_task_id: '9223372036854775807', status: 'CANCELED' })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await deleteDdlTask('9223372036854775807')
    const call = lastCall(fetchMock)
    expect(call.url).toBe('/api/v1/ddl/tasks/9223372036854775807')
    expect(result.deleted_task_id).toBe('9223372036854775807')
  })

  it('错误信封保留后端稳定错误码', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        envelope(null, 'DRAFT_EXPIRED_OR_NOT_FOUND', '变更草案已过期或不存在。'),
        404,
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      decideDdlDraft('102', { decision: 'CONFIRM_UPDATE', client_request_id: 'req_draft_2' }),
    ).rejects.toMatchObject({
      code: 'DRAFT_EXPIRED_OR_NOT_FOUND',
      httpStatus: 404,
      traceId: 'tr_ddltest01',
    })

    await expect(
      decideDdlDraft('102', { decision: 'DISCARD', client_request_id: 'req_draft_3' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})
