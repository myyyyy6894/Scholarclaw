import { describe, expect, it } from 'vitest'

import type { DdlTask, DdlTaskBoard, TaskChangeDraft } from '@/api/ddl'
import {
  boardCounts,
  buildCreatePayload,
  buildUpdatePayload,
  countdown,
  deadlineText,
  draftDiffRows,
  draftExpiryText,
  draftNewDeadline,
  emptyTaskForm,
  findTask,
  formatDueAt,
  formatTimestamp,
  humanizeDuration,
  inferredNotice,
  isUuidLike,
  isValidCalendarDate,
  isValidClockTime,
  newClientRequestId,
  normalizeClockTime,
  pendingReason,
  precisionLabel,
  rawTextError,
  statusLabel,
  statusTagType,
  taskFormError,
  taskFormFrom,
  timeFields,
  type DdlTaskFormState,
} from '@/views/ddl/ddl-task'

function task(overrides: Partial<DdlTask> = {}): DdlTask {
  return {
    task_id: '102',
    display_code: '#T102',
    course_id: '2002',
    course_name: '操作系统原理',
    task_title: '提交实验二报告与源码',
    deliverable_format: '学号_姓名.zip',
    submission_channel: '学习通作业区',
    raw_source_text: '各位同学，本周五下午5点前请将实验二报告发至学习通。',
    raw_time_text: '本周五下午5点前',
    time_precision: 'EXACT_DATETIME',
    is_relative_time: true,
    due_date: '2026-10-16',
    due_time: '17:00:00',
    due_at: '2026-10-16T17:00:00+08:00',
    time_inferred: false,
    user_timezone: 'Asia/Shanghai',
    confidence: 0.96,
    status: 'ACTIVE',
    follow_up_at: null,
    follow_up_count: 0,
    reminded_t24h: false,
    reminded_t6h: false,
    reminded_overdue: false,
    source_platform: 'WEB',
    completed_at: null,
    created_at: '2026-10-15T11:40:02+08:00',
    updated_at: '2026-10-15T11:40:02+08:00',
    ...overrides,
  }
}

function draft(overrides: Partial<TaskChangeDraft> = {}): TaskChangeDraft {
  return {
    target_task_id: '102',
    new_task_title: '提交实验二报告与源码（延期）',
    new_time_precision: 'EXACT_DATETIME',
    new_due_date: '2026-10-18',
    new_due_time: '23:59:00',
    new_due_at: '2026-10-18T23:59:00+08:00',
    new_time_inferred: false,
    new_deliverable_format: '学号_姓名.zip',
    new_submission_channel: '学习通作业区',
    raw_source_text: '通知：操作系统实验二延期到 10 月 18 日 23:59 截止。',
    raw_time_text: '10 月 18 日 23:59',
    expires_at: '2026-10-16T12:00:00+08:00',
    ...overrides,
  }
}

function form(overrides: Partial<DdlTaskFormState> = {}): DdlTaskFormState {
  return { ...emptyTaskForm(), ...overrides }
}

describe('status and precision labels', () => {
  it('六态都有中文标签与稳定的标签色', () => {
    expect(statusLabel('PENDING_TIME')).toBe('待补充时间')
    expect(statusLabel('PENDING_CONFIRM')).toBe('待确认')
    expect(statusLabel('ACTIVE')).toBe('进行中')
    expect(statusLabel('OVERDUE')).toBe('已逾期')
    expect(statusLabel('COMPLETED')).toBe('已完成')
    expect(statusLabel('CANCELED')).toBe('已取消')
    expect(statusTagType('OVERDUE')).toBe('danger')
    expect(statusTagType('ACTIVE')).toBe('primary')
    expect(statusTagType('CANCELED')).toBe('info')
  })

  it('四级时间精度有中文说明', () => {
    expect(precisionLabel('EXACT_DATETIME')).toBe('精确到时刻')
    expect(precisionLabel('DATE_ONLY')).toBe('仅日期')
    expect(precisionLabel('VAGUE_OR_MISSING')).toBe('未明确时间')
    expect(precisionLabel('INVALID_OR_PAST')).toBe('非法或已过期')
  })

  it('待确认原因不推测具体时间', () => {
    expect(pendingReason({ status: 'PENDING_TIME' })).toContain('未给出可用的截止时间')
    expect(pendingReason({ status: 'PENDING_CONFIRM' })).toContain('核对后确认')
  })
})

describe('raw text validation', () => {
  it('长度边界为 4 ~ 4000 且按 trim 后计算', () => {
    expect(rawTextError('   ')).toContain('至少 4')
    expect(rawTextError(' 你好 ')).toContain('至少 4')
    expect(rawTextError('今天交报告')).toBeNull()
    expect(rawTextError('a'.repeat(4001))).toContain('4000')
    expect(rawTextError(` ${'a'.repeat(4000)} `)).toBeNull()
  })
})

describe('client request id', () => {
  it('每次生成新值且带前缀', () => {
    const first = newClientRequestId()
    const second = newClientRequestId()
    expect(first).not.toBe(second)
    expect(first.startsWith('req_ddl_')).toBe(true)
    // 后端要求 8 ~ 128 字符
    expect(first.length).toBeGreaterThanOrEqual(8)
    expect(first.length).toBeLessThanOrEqual(128)
    expect(isUuidLike(first.slice('req_ddl_'.length))).toBe(true)
  })
})

describe('time display', () => {
  it('按任务记录的用户时区展示截止时间与星期', () => {
    expect(formatDueAt('2026-10-16T17:00:00+08:00', 'Asia/Shanghai')).toBe(
      '2026-10-16（周五）17:00',
    )
    expect(formatDueAt('2026-10-16T17:00:00+08:00', 'UTC')).toBe('2026-10-16（周五）09:00')
  })

  it('缺失或非法时间返回 null，不补造', () => {
    expect(formatDueAt(null, 'Asia/Shanghai')).toBeNull()
    expect(formatDueAt('not-a-date', 'Asia/Shanghai')).toBeNull()
    expect(formatDueAt('2026-10-16T17:00:00+08:00', 'Not/AZone')).not.toBeNull()
  })

  it('没有 due_at 时明确显示未生成截止时间', () => {
    expect(deadlineText(task({ due_at: null, status: 'PENDING_TIME' }))).toBe('未生成截止时间')
    expect(deadlineText(task())).toBe('2026-10-16（周五）17:00')
  })

  it('只有 DATE_ONLY 才提示按 23:59 统计', () => {
    expect(inferredNotice(task({ time_inferred: true }))).toContain('23:59')
    expect(inferredNotice(task({ time_inferred: false }))).toBeNull()
  })

  it('系统时间戳格式稳定', () => {
    expect(formatTimestamp('2026-10-15T11:40:02+08:00')).toMatch(/^2026-10-15 \d{2}:40$/)
    expect(formatTimestamp(null)).toBeNull()
  })

  it('倒计时对已逾期任务是“已超过”，且不改后端状态', () => {
    const now = Date.parse('2026-10-16T15:00:00+08:00')
    expect(countdown(task(), now)).toEqual({ text: '剩余 2 小时', overdue: false })

    const overdue = countdown(task(), Date.parse('2026-10-16T18:30:00+08:00'))
    expect(overdue?.overdue).toBe(true)
    expect(overdue?.text).toBe('已超过 1 小时 30 分钟')

    expect(countdown(task({ status: 'COMPLETED' }), now)).toBeNull()
    expect(countdown(task({ due_at: null, status: 'PENDING_TIME' }), now)).toBeNull()
  })

  it('时长人类可读', () => {
    expect(humanizeDuration(30_000)).toBe('不足 1 分钟')
    expect(humanizeDuration(5 * 60_000)).toBe('5 分钟')
    expect(humanizeDuration(90 * 60_000)).toBe('1 小时 30 分钟')
    expect(humanizeDuration(2 * 24 * 3600_000)).toBe('2 天')
    expect(humanizeDuration(26 * 3600_000)).toBe('1 天 2 小时')
  })
})

describe('date and clock validation', () => {
  it('拦截非法公历日期', () => {
    expect(isValidCalendarDate('2026-02-29')).toBe(false)
    expect(isValidCalendarDate('2024-02-29')).toBe(true)
    expect(isValidCalendarDate('2026-04-31')).toBe(false)
    expect(isValidCalendarDate('2026-13-01')).toBe(false)
    expect(isValidCalendarDate('2026-1-1')).toBe(false)
  })

  it('时刻校验与归一化', () => {
    expect(isValidClockTime('23:59')).toBe(true)
    expect(isValidClockTime('23:59:59')).toBe(true)
    expect(isValidClockTime('24:00')).toBe(false)
    expect(isValidClockTime('12:60')).toBe(false)
    expect(normalizeClockTime('17:05')).toBe('17:05:00')
    expect(normalizeClockTime('17:05:09')).toBe('17:05:09')
  })
})

describe('task form validation', () => {
  const base = form({ courseName: '操作系统原理', taskTitle: '提交实验二报告' })

  it('精确到时刻必须有合法日期与时刻', () => {
    expect(taskFormError(base)).toContain('截止日期')
    expect(taskFormError({ ...base, dueDate: '2026-02-29' })).toContain('合法公历')
    expect(taskFormError({ ...base, dueDate: '2026-10-16' })).toContain('具体时刻')
    expect(taskFormError({ ...base, dueDate: '2026-10-16', dueTime: '17:00' })).toBeNull()
  })

  it('仅日期不要求时刻，且不携带时刻', () => {
    const dateOnly = { ...base, timePrecision: 'DATE_ONLY' as const, dueDate: '2026-10-16', dueTime: '17:00' }
    expect(taskFormError(dateOnly)).toBeNull()
    expect(timeFields(dateOnly)).toEqual({ due_date: '2026-10-16', due_time: null })
  })

  it('暂不设定时间会清空时间字段', () => {
    const vague = { ...base, timePrecision: 'VAGUE_OR_MISSING' as const, dueDate: '2026-10-16' }
    expect(taskFormError(vague)).toBeNull()
    expect(timeFields(vague)).toEqual({ due_date: null, due_time: null })
  })

  it('课程名与标题必填且限长', () => {
    expect(taskFormError({ ...base, courseName: ' ' })).toContain('课程名称')
    expect(taskFormError({ ...base, taskTitle: ' ' })).toContain('任务标题')
    expect(taskFormError({ ...base, taskTitle: 'a'.repeat(256) })).toContain('255')
    expect(taskFormError({ ...base, submissionChannel: 'a'.repeat(256) })).toContain('截止日期')
    expect(
      taskFormError({
        ...base,
        dueDate: '2026-10-16',
        dueTime: '17:00',
        submissionChannel: 'a'.repeat(256),
      }),
    ).toContain('提交渠道')
  })
})

describe('request payload builders', () => {
  it('新建请求清空空的可选字段并 trim', () => {
    const payload = buildCreatePayload(
      form({
        courseId: ' 2001 ',
        courseName: ' 数据库系统概念 ',
        taskTitle: ' 完成第 3 次作业 ',
        timePrecision: 'DATE_ONLY',
        dueDate: '2026-10-25',
        dueTime: '22:00:00',
        deliverableFormat: '  ',
        submissionChannel: ' Canvas ',
      }),
      'req_manual_1',
    )
    expect(payload).toEqual({
      client_request_id: 'req_manual_1',
      course_id: '2001',
      course_name: '数据库系统概念',
      task_title: '完成第 3 次作业',
      time_precision: 'DATE_ONLY',
      due_date: '2026-10-25',
      due_time: null,
      deliverable_format: null,
      submission_channel: 'Canvas',
    })
  })

  it('编辑请求不携带 client_request_id，课程可置空', () => {
    const payload = buildUpdatePayload(
      form({
        courseId: '',
        courseName: '未分类课程',
        taskTitle: '补交实验报告',
        timePrecision: 'EXACT_DATETIME',
        dueDate: '2026-10-25',
        dueTime: '22:00',
      }),
    )
    expect(payload.course_id).toBeNull()
    expect(payload.due_time).toBe('22:00:00')
    expect('client_request_id' in payload).toBe(false)
  })

  it('从任务回填表单：非法的历史精度按是否有日期降级', () => {
    expect(taskFormFrom(task({ time_precision: 'INVALID_OR_PAST', due_time: null })).timePrecision).toBe(
      'DATE_ONLY',
    )
    expect(
      taskFormFrom(task({ time_precision: 'INVALID_OR_PAST', due_date: null, due_time: null }))
        .timePrecision,
    ).toBe('VAGUE_OR_MISSING')
    expect(taskFormFrom(task({ due_time: '17:00' })).dueTime).toBe('17:00:00')
  })
})

describe('draft comparison', () => {
  it('只标记真实变化的字段', () => {
    const rows = draftDiffRows(task(), draft())
    const changed = rows.filter((row) => row.changed).map((row) => row.label)
    expect(changed).toEqual(['任务标题', '截止日期', '截止时刻'])
    expect(rows.find((row) => row.label === '交付物格式')?.changed).toBe(false)
  })

  it('原任务缺失时仍能展示草案侧', () => {
    const rows = draftDiffRows(null, draft())
    expect(rows.find((row) => row.label === '任务标题')?.before).toBe('—')
    expect(rows.find((row) => row.label === '任务标题')?.after).toContain('延期')
  })

  it('草案有效期与新截止时间可读', () => {
    expect(draftExpiryText(draft())).toMatch(/^草案有效期至 2026-10-16 \d{2}:00$/)
    expect(draftNewDeadline(draft())).toBe('2026-10-18（周日）23:59')
  })
})

describe('board helpers', () => {
  const board: DdlTaskBoard = {
    pending_inbox_tasks: [task({ task_id: '1', status: 'PENDING_TIME' })],
    pending_change_drafts: [draft()],
    active_tasks: [
      task({ task_id: '2', status: 'ACTIVE' }),
      task({ task_id: '3', status: 'OVERDUE' }),
    ],
    completed_tasks: [task({ task_id: '4', status: 'COMPLETED' })],
  }

  it('统计各分组数量', () => {
    expect(boardCounts(board)).toEqual({
      pending: 1,
      drafts: 1,
      active: 1,
      overdue: 1,
      completed: 1,
    })
  })

  it('按任务 ID 命中任意分组', () => {
    expect(findTask(board, '3')?.status).toBe('OVERDUE')
    expect(findTask(board, '4')?.status).toBe('COMPLETED')
    expect(findTask(board, '999')).toBeNull()
  })
})
