import { describe, expect, it } from 'vitest'

import type { CourseNamespace } from '@/api/course'
import type { DdlTask, DdlTaskBoard } from '@/api/ddl'
import type { KnowledgeDoc } from '@/api/knowledge'
import type { NotificationItem } from '@/api/notifications'
import type { ChatMessage } from '@/api/rag'
import {
  buildCalendarCells,
  buildMonthGrid,
  compactDeadline,
  courseReadiness,
  formatDateLabel,
  greetingForHour,
  groupDdlCountByCourse,
  groupTasksByDate,
  mergeNoticeItems,
  pickDefaultCourse,
  recentChatPairs,
  recentDocs,
  relativeTimeLabel,
  sortActiveTasks,
  todayTasks,
  toDateString,
  zonedDateParts,
} from '@/views/home/dashboard-data'

function course(id: string, overrides: Partial<CourseNamespace> = {}): CourseNamespace {
  return {
    course_id: id,
    course_name: `课程 ${id}`,
    description: null,
    is_default: false,
    embedding_provider: 'OPENAI_COMPATIBLE',
    embedding_model: 'text-embedding-3-small',
    embedding_dim: 1536,
    index_version: 1,
    index_status: 'ACTIVE',
    ready_doc_count: 0,
    parsing_doc_count: 0,
    failed_doc_count: 0,
    created_at: '2026-10-01T10:00:00+08:00',
    updated_at: '2026-10-01T10:00:00+08:00',
    ...overrides,
  }
}

function task(id: string, overrides: Partial<DdlTask> = {}): DdlTask {
  return {
    task_id: id,
    display_code: `T${id}`,
    course_id: 'c1',
    course_name: '数据结构',
    task_title: `任务 ${id}`,
    deliverable_format: null,
    submission_channel: null,
    raw_source_text: null,
    raw_time_text: null,
    time_precision: 'EXACT_DATETIME',
    is_relative_time: false,
    due_date: '2026-11-01',
    due_time: '23:59:00',
    due_at: '2026-11-01T23:59:00+08:00',
    time_inferred: false,
    time_ambiguity_reason: null,
    user_timezone: 'Asia/Shanghai',
    confidence: 1,
    status: 'ACTIVE',
    follow_up_at: null,
    follow_up_count: 0,
    reminded_t24h: false,
    reminded_t6h: false,
    reminded_overdue: false,
    source_platform: 'WEB',
    completed_at: null,
    created_at: '2026-10-30T10:00:00+08:00',
    updated_at: '2026-10-30T10:00:00+08:00',
    ...overrides,
  }
}

function board(overrides: Partial<DdlTaskBoard> = {}): DdlTaskBoard {
  return {
    pending_inbox_tasks: [],
    pending_change_drafts: [],
    active_tasks: [],
    completed_tasks: [],
    ...overrides,
  }
}

function doc(id: string, overrides: Partial<KnowledgeDoc> = {}): KnowledgeDoc {
  return {
    doc_id: id,
    course_id: 'c1',
    doc_name: `课件 ${id}.pdf`,
    file_ext: 'pdf',
    file_size_bytes: 1024,
    status: 'READY',
    parse_version: 1,
    course_index_version: 1,
    indexed_embedding_model: 'text-embedding-3-small',
    indexed_embedding_dim: 1536,
    page_count: 10,
    chunk_count: 20,
    retry_count: 0,
    last_ingest_job_id: null,
    error_code: null,
    error_msg: null,
    enqueued_at: null,
    ready_at: '2026-10-30T10:00:00+08:00',
    created_at: '2026-10-29T10:00:00+08:00',
    updated_at: '2026-10-30T10:00:00+08:00',
    ...overrides,
  }
}

function notification(
  id: string,
  overrides: Partial<NotificationItem> = {},
): NotificationItem {
  return {
    notification_id: id,
    biz_type: 'DDL_T24H',
    biz_id: '1',
    title: `通知 ${id}`,
    content: '内容',
    read_status: 'UNREAD',
    outbound_status: 'FALLBACK_WEB_ONLY',
    sent_im_at: null,
    read_at: null,
    created_at: '2026-10-30T10:00:00+08:00',
    ...overrides,
  }
}

function message(
  id: string,
  role: ChatMessage['role'],
  content: string,
  createdAt = '2026-10-30T10:00:00+08:00',
): ChatMessage {
  return {
    message_id: id,
    course_id: 'c1',
    platform: 'WEB',
    role,
    intent_type: null,
    content,
    citations: null,
    is_rejected: false,
    trace_id: null,
    retrieval_cost_ms: null,
    llm_first_token_ms: null,
    created_at: createdAt,
  }
}

describe('course readiness', () => {
  it('没有文档时返回 null，而不是 0%', () => {
    expect(courseReadiness(course('c1'))).toEqual({ ready: 0, total: 0, percent: null })
  })

  it('按 READY / 总数计算并四舍五入', () => {
    const result = courseReadiness(
      course('c1', { ready_doc_count: 2, parsing_doc_count: 2, failed_doc_count: 1 }),
    )
    expect(result).toEqual({ ready: 2, total: 5, percent: 40 })
  })

  it('全部就绪时为 100%，全部失败时为 0%', () => {
    expect(
      courseReadiness(course('c1', { ready_doc_count: 3 })).percent,
    ).toBe(100)
    expect(
      courseReadiness(course('c1', { failed_doc_count: 2 })).percent,
    ).toBe(0)
  })
})

describe('ddl dashboard aggregation', () => {
  it('进行中任务按截止时间升序，缺失截止时间排最后', () => {
    const tasks = sortActiveTasks(
      board({
        active_tasks: [
          task('3', { due_at: null, due_date: null, due_time: null }),
          task('2', { due_at: '2026-11-05T12:00:00+08:00' }),
          task('1', { due_at: '2026-11-02T12:00:00+08:00' }),
          task('4', { status: 'COMPLETED' }),
        ],
      }),
    )
    expect(tasks.map((item) => item.task_id)).toEqual(['1', '2', '3'])
  })

  it('今日待办最多取 5 条', () => {
    const tasks = Array.from({ length: 8 }, (_, index) =>
      task(String(index), {
        due_at: `2026-11-0${(index % 9) + 1}T10:00:00+08:00`,
      }),
    )
    expect(todayTasks(board({ active_tasks: tasks }), 5)).toHaveLength(5)
  })

  it('DDL 数按课程聚合，跨分组去重并跳过无课程任务', () => {
    const counts = groupDdlCountByCourse(
      board({
        pending_inbox_tasks: [task('1'), task('2', { course_id: 'c2' })],
        active_tasks: [task('1'), task('3')],
        completed_tasks: [task('4', { course_id: null })],
      }),
    )
    expect(counts).toEqual({ c1: 2, c2: 1 })
  })
})

describe('recent docs and chats', () => {
  it('排除已删除/删除中文档并按更新时间降序', () => {
    const docs = recentDocs([
      doc('1', { updated_at: '2026-10-29T10:00:00+08:00' }),
      doc('2', { updated_at: '2026-10-31T10:00:00+08:00' }),
      doc('3', { status: 'DELETED' }),
      doc('4', { status: 'DELETING' }),
    ])
    expect(docs.map((item) => item.doc_id)).toEqual(['2', '1'])
  })

  it('从尾部配对问答，返回最新在前', () => {
    const pairs = recentChatPairs([
      message('u1', 'USER', '问题一'),
      message('a1', 'ASSISTANT', '回答一'),
      message('u2', 'USER', '问题二'),
      message('a2', 'ASSISTANT', '回答二'),
    ])
    expect(pairs.map((pair) => [pair.user.message_id, pair.assistant.message_id])).toEqual([
      ['u2', 'a2'],
      ['u1', 'a1'],
    ])
  })

  it('没有用户提问的助手消息不配对', () => {
    const pairs = recentChatPairs([
      message('a1', 'ASSISTANT', '孤立回答'),
      message('u1', 'USER', '问题'),
      message('a2', 'ASSISTANT', '回答'),
    ])
    expect(pairs).toHaveLength(1)
    expect(pairs[0]?.user.message_id).toBe('u1')
  })

  it('消息乱序时按 created_at 重新排序', () => {
    const pairs = recentChatPairs([
      message('u1', 'USER', '较早问题', '2026-10-30T09:00:00+08:00'),
      message('a1', 'ASSISTANT', '较早回答', '2026-10-30T09:00:01+08:00'),
      message('u2', 'USER', '较新问题', '2026-10-31T09:00:00+08:00'),
      message('a2', 'ASSISTANT', '较新回答', '2026-10-31T09:00:01+08:00'),
    ])
    expect(pairs[0]?.user.message_id).toBe('u2')
  })
})

describe('compact deadline', () => {
  it('只显示月日与时刻', () => {
    expect(compactDeadline(task('1'))).toBe('截止 11-01 23:59')
    expect(compactDeadline(task('2', { due_time: null }))).toBe('截止 11-01')
    expect(compactDeadline(task('3', { due_date: null }))).toBe('未生成截止时间')
  })
})

describe('notifications', () => {
  it('未读优先、按 id 去重并截断', () => {
    const merged = mergeNoticeItems(
      [notification('1', { read_status: 'READ' }), notification('2')],
      [notification('2'), notification('3'), notification('4'), notification('5')],
      4,
    )
    expect(merged.map((item) => item.notification_id)).toEqual(['2', '3', '4', '5'])
  })
})

describe('timezone and greeting', () => {
  it('问候语按小时分段', () => {
    expect(greetingForHour(6)).toBe('早上好')
    expect(greetingForHour(10)).toBe('上午好')
    expect(greetingForHour(12)).toBe('中午好')
    expect(greetingForHour(15)).toBe('下午好')
    expect(greetingForHour(20)).toBe('晚上好')
    expect(greetingForHour(2)).toBe('夜深了')
  })

  it('按 Asia/Shanghai 取墙钟，不受宿主时区影响', () => {
    const parts = zonedDateParts(new Date('2026-10-31T16:30:00Z'), 'Asia/Shanghai')
    expect(parts).toMatchObject({
      year: 2026,
      month: 11,
      day: 1,
      hour: 0,
      minute: 30,
      weekday: 0,
    })
    expect(formatDateLabel(parts)).toBe('2026年11月1日 周日')
  })

  it('非法时区回落到默认时区', () => {
    const parts = zonedDateParts(new Date('2026-10-31T16:30:00Z'), 'Not/AZone')
    expect(parts.day).toBe(1)
    expect(parts.hour).toBe(0)
  })
})

describe('ddl calendar', () => {
  it('2026 年 11 月从周一开始的网格含 30 个本月日期', () => {
    const cells = buildMonthGrid(2026, 11, '2026-11-01')
    expect(cells).toHaveLength(42)
    expect(cells[0]?.date).toBe('2026-10-26')
    expect(cells.filter((cell) => cell.inMonth)).toHaveLength(30)
    const today = cells.find((cell) => cell.isToday)
    expect(today?.date).toBe('2026-11-01')
  })

  it('只按 due_date 聚合任务', () => {
    const grouped = groupTasksByDate([
      task('1', { due_date: '2026-11-01' }),
      task('2', { due_date: '2026-11-01' }),
      task('3', { due_date: null }),
    ])
    expect(grouped.get('2026-11-01')?.map((item) => item.task_id)).toEqual(['1', '2'])
    expect(grouped.has('')).toBe(false)
  })

  it('日历单元格挂载当天任务', () => {
    const cells = buildCalendarCells(
      [task('1', { due_date: '2026-11-15' })],
      2026,
      11,
      '2026-11-01',
    )
    const cell = cells.find((item) => item.date === '2026-11-15')
    expect(cell?.tasks.map((item) => item.task_id)).toEqual(['1'])
  })

  it('日期格式化补零', () => {
    expect(toDateString(2026, 1, 5)).toBe('2026-01-05')
  })
})

describe('relative time and course pick', () => {
  const now = Date.parse('2026-11-01T12:00:00+08:00')

  it('相对时间按分钟/小时/天递进', () => {
    expect(relativeTimeLabel('2026-11-01T11:59:45+08:00', now)).toBe('刚刚')
    expect(relativeTimeLabel('2026-11-01T11:55:00+08:00', now)).toBe('5 分钟前')
    expect(relativeTimeLabel('2026-11-01T10:00:00+08:00', now)).toBe('2 小时前')
    expect(relativeTimeLabel('2026-10-29T12:00:00+08:00', now)).toBe('3 天前')
    expect(relativeTimeLabel('2026-10-01T12:00:00+08:00', now)).toBe('2026-10-01')
  })

  it('优先当前选中课程，否则第一门', () => {
    const courses = [course('c1'), course('c2')]
    expect(pickDefaultCourse(courses, 'c2')?.course_id).toBe('c2')
    expect(pickDefaultCourse(courses, 'missing')?.course_id).toBe('c1')
    expect(pickDefaultCourse([], 'c1')).toBeNull()
  })
})
