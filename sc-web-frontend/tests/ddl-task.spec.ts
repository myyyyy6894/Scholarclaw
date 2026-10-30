import { readFileSync } from 'node:fs'

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
  emptyBoard,
  isStaleExtractResponse,
  editExtractInput,
  emptyTaskForm,
  findTask,
  forceCreateOperation,
  manualCreateSubmitState,
  markExtractSubmitted,
  newExtractOperation,
  newManualCreateOperation,
  originalTimeInfo,
  pendingCardInfo,
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
  resetManualCreateAfterConflict,
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
    time_ambiguity_reason: null,
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
    new_time_ambiguity_reason: null,
    new_deliverable_format: '学号_姓名.zip',
    new_submission_channel: '学习通作业区',
    raw_source_text: '通知：操作系统实验二延期到 10 月 18 日 23:59 截止。',
    raw_time_text: '10 月 18 日 23:59',
    user_timezone: 'Asia/Shanghai',
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
      '2026-10-16（周五）17:00（Asia/Shanghai）',
    )
    expect(formatDueAt('2026-10-16T17:00:00+08:00', 'UTC')).toBe(
      '2026-10-16（周五）09:00（UTC）',
    )
  })

  it('缺失或非法时间返回 null，不补造', () => {
    expect(formatDueAt(null, 'Asia/Shanghai')).toBeNull()
    expect(formatDueAt('not-a-date', 'Asia/Shanghai')).toBeNull()
    expect(formatDueAt('2026-10-16T17:00:00+08:00', 'Not/AZone')).toBe(
      '2026-10-16（周五）17:00（时区无效（Not/AZone），按 Asia/Shanghai 显示）',
    )
    // 缺失时区必须显式标注默认值，而不是静默使用浏览器时区。
    expect(formatDueAt('2026-10-16T17:00:00+08:00', null)).toBe(
      '2026-10-16（周五）17:00（时区缺失，按 Asia/Shanghai 显示）',
    )
  })

  it('没有 due_at 时明确显示未生成截止时间', () => {
    expect(deadlineText(task({ due_at: null, status: 'PENDING_TIME' }))).toBe('未生成截止时间')
    expect(deadlineText(task())).toBe('2026-10-16（周五）17:00（Asia/Shanghai）')
  })

  it('只有 DATE_ONLY 才提示按 23:59 统计', () => {
    expect(inferredNotice(task({ time_inferred: true }))).toContain('23:59')
    expect(inferredNotice(task({ time_inferred: false }))).toBeNull()
  })

  it('系统时间戳格式稳定', () => {
    expect(formatTimestamp('2026-10-15T11:40:02+08:00')).toMatch(/^2026-10-15 \d{2}:40$/)
    expect(formatTimestamp('2026-10-15T11:40:02+08:00', 'UTC')).toMatch(/^2026-10-15 03:40$/)
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
    // 过去时间但原文有明确时刻：必须保留 EXACT_DATETIME，不能降级为 DATE_ONLY
    const pastExact = taskFormFrom(task({ time_precision: 'INVALID_OR_PAST' }))
    expect(pastExact.timePrecision).toBe('EXACT_DATETIME')
    expect(pastExact.dueTime).toBe('17:00:00')
    expect(
      taskFormFrom(task({ time_precision: 'INVALID_OR_PAST', due_time: null })).timePrecision,
    ).toBe('DATE_ONLY')
    expect(taskFormFrom(task({ due_time: '17:00' })).dueTime).toBe('17:00:00')
  })
})

describe('draft comparison', () => {
  it('只标记真实变化的字段', () => {
    const rows = draftDiffRows(task(), draft())
    const changed = rows.filter((row) => row.changed).map((row) => row.label)
    expect(changed).toEqual(['任务标题', '截止日期', '截止时刻', '截止时间（含时区）'])
    expect(rows.find((row) => row.label === '交付物格式')?.changed).toBe(false)
  })

  it('原任务缺失时仍能展示草案侧', () => {
    const rows = draftDiffRows(null, draft())
    expect(rows.find((row) => row.label === '任务标题')?.before).toBe('—')
    expect(rows.find((row) => row.label === '任务标题')?.after).toContain('延期')
  })

  it('草案有效期与新截止时间可读', () => {
    expect(draftExpiryText(draft())).toMatch(/^草案有效期至 2026-10-16 \d{2}:00$/)
    expect(draftNewDeadline(draft())).toBe('2026-10-18（周日）23:59（Asia/Shanghai）')
  })
})

describe('pending confirmation card', () => {
  it('周末“下周一”展示公历日期、星期与后端确认原因', () => {
    const info = pendingCardInfo(task({
      status: 'PENDING_CONFIRM',
      due_date: '2026-10-26',
      due_time: '22:00:00',
      due_at: '2026-10-26T22:00:00+08:00',
      raw_time_text: '下周一 22:00',
      time_ambiguity_reason: '周六/周日收到“下周一”，已按下一周周一换算，请核对',
    }))

    expect(info.deadline).toBe('2026-10-26（周一）22:00（Asia/Shanghai）')
    expect(info.rawTime).toBe('下周一 22:00')
    expect(info.reason).toContain('下周一')
    expect(info.genericReason).toBe(false)
  })

  it('凌晨跨日展示换算后的公历日期与星期', () => {
    const info = pendingCardInfo(task({
      status: 'PENDING_CONFIRM',
      due_date: '2026-10-16',
      due_time: '02:00:00',
      due_at: '2026-10-16T02:00:00+08:00',
      raw_time_text: '今晚 2 点',
      time_ambiguity_reason: '凌晨 00:00-04:00 收到“今天/明天/今晚”，日期可能跨日，请核对',
    }))

    expect(info.deadline).toBe('2026-10-16（周五）02:00（Asia/Shanghai）')
    expect(info.reason).toContain('跨日')
  })

  it('跨年顺延展示顺延年份与核对提示', () => {
    const info = pendingCardInfo(task({
      status: 'PENDING_CONFIRM',
      due_date: '2027-01-05',
      due_time: '22:00:00',
      due_at: '2027-01-05T22:00:00+08:00',
      raw_time_text: '1月5日 22:00',
      time_ambiguity_reason: '已按跨年顺延至次年，请核对',
    }))

    expect(info.deadline).toBe('2027-01-05（周二）22:00（Asia/Shanghai）')
    expect(info.reason).toContain('跨年顺延')
  })

  it('PENDING_TIME 不补造截止时间，并给出通用原因', () => {
    const info = pendingCardInfo(task({
      status: 'PENDING_TIME',
      time_precision: 'VAGUE_OR_MISSING',
      due_date: null,
      due_time: null,
      due_at: null,
      raw_time_text: '尽快',
      time_ambiguity_reason: null,
    }))

    expect(info.deadline).toBe('未生成截止时间')
    expect(info.rawTime).toBe('尽快')
    expect(info.reason).toContain('未给出可用的截止时间')
    expect(info.genericReason).toBe(true)
  })
})

describe('draft deadline timezone', () => {
  it('优先使用草案自带的用户时区快照', () => {
    expect(draftNewDeadline(draft(), 'Asia/Shanghai')).toBe('2026-10-18（周日）23:59（Asia/Shanghai）')
    // 即使调用方传了别的时区，草案快照仍然优先，避免同一草案在不同电脑显示不同时刻。
    expect(draftNewDeadline(draft(), 'America/New_York')).toBe(
      '2026-10-18（周日）23:59（Asia/Shanghai）',
    )
  })

  it('草案快照缺失时使用调用方提供的目标时区，而不是浏览器时区', () => {
    expect(draftNewDeadline(draft({ user_timezone: null }), 'America/New_York'))
      .toBe('2026-10-18（周日）11:59（America/New_York）')
    // 草案与调用方都没有时区时，必须显式标注按默认时区显示，不能静默使用浏览器时区。
    expect(draftNewDeadline(draft({ user_timezone: null }), null))
      .toBe('2026-10-18（周日）23:59（时区缺失，按 Asia/Shanghai 显示）')
  })
})

describe('extract operation state machine', () => {
  it('未提交时修改输入保留同一请求 ID', () => {
    const operation = newExtractOperation(' 原文 A ', ' 2001 ', 'req_1')
    expect(operation.rawText).toBe('原文 A')
    expect(operation.courseId).toBe('2001')

    const edited = editExtractInput(operation, '原文 B', '2001', 'req_2')
    expect(edited.requestId).toBe('req_1')
    expect(edited.rawText).toBe('原文 B')
    expect(edited.forceCreate).toBe(false)
  })

  it('已提交后修改输入生成新 ID 并重置 forceCreate', () => {
    const submitted = markExtractSubmitted(
      forceCreateOperation(newExtractOperation('原文 A', '', 'req_1'), 'req_force_1'),
    )
    expect(submitted.forceCreate).toBe(true)
    expect(submitted.requestId).toBe('req_force_1')

    const edited = editExtractInput(submitted, '原文 B', '', 'req_2')
    expect(edited.requestId).toBe('req_2')
    expect(edited.forceCreate).toBe(false)
    expect(edited.submitted).toBe(false)
  })

  it('force 操作失败重试复用同一新 ID', () => {
    const submitted = markExtractSubmitted(newExtractOperation('原文 A', '', 'req_1'))
    const forced = forceCreateOperation(submitted, 'req_force_1')
    const retried = forceCreateOperation(forced, 'req_force_2')

    expect(retried).toBe(forced)
    expect(retried.requestId).toBe('req_force_1')
    expect(retried.forceCreate).toBe(true)
  })

  it('输入与 force 未变化时复用同一请求 ID', () => {
    const operation = markExtractSubmitted(newExtractOperation('原文 A', '2001', 'req_1'))
    expect(editExtractInput(operation, '原文 A', '2001', 'req_2')).toBe(operation)
    expect(operation.requestId).toBe('req_1')
  })

  it('慢响应期间输入已变化：旧请求 ID 的响应必须判定为过期', () => {
    const submitted = markExtractSubmitted(newExtractOperation('原文 A', '2001', 'req_1'))
    const edited = editExtractInput(submitted, '原文 B', '2001', 'req_2')

    expect(isStaleExtractResponse(submitted, submitted.requestId)).toBe(false)
    expect(isStaleExtractResponse(edited, submitted.requestId)).toBe(true)
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

describe('pending DATE_ONLY card', () => {
  it('DATE_ONLY 待确认卡片标记 23:59 推断说明', () => {
    const pending = task({
      status: 'PENDING_CONFIRM',
      time_precision: 'DATE_ONLY',
      due_time: null,
      due_at: '2026-10-18T23:59:59+08:00',
      time_inferred: true,
    })

    const info = pendingCardInfo(pending)

    expect(info.timeInferred).toBe(true)
    expect(info.deadline).toContain('2026-10-18')
    expect(info.deadline).toContain('23:59')
    expect(inferredNotice(pending)).toContain('23:59')
  })

  it('非推断任务不显示 23:59 说明', () => {
    expect(pendingCardInfo(task({ time_inferred: false })).timeInferred).toBe(false)
    expect(inferredNotice(task({ time_inferred: false }))).toBeNull()
  })
})

describe('draft timezone snapshot', () => {
  it('草案截止时间按草案自带的用户时区展示，而不是浏览器时区', () => {
    const newYork = draft({ user_timezone: 'America/New_York' })

    expect(draftNewDeadline(newYork, null)).toBe('2026-10-18（周日）11:59（America/New_York）')
  })

  it('草案没有时区快照时不得静默使用浏览器时区', () => {
    const missing = draft({ user_timezone: null })

    expect(draftNewDeadline(missing, null)).toBe(
      '2026-10-18（周日）23:59（时区缺失，按 Asia/Shanghai 显示）',
    )
  })

  it('目标课程被过滤掉时仍可显示草案时间（不依赖 board 中的目标任务）', () => {
    const filteredBoard: DdlTaskBoard = {
      pending_inbox_tasks: [],
      pending_change_drafts: [draft()],
      active_tasks: [],
      completed_tasks: [],
    }

    expect(findTask(filteredBoard, '102')).toBeNull()
    expect(draftNewDeadline(filteredBoard.pending_change_drafts[0], null)).toBe(
      '2026-10-18（周日）23:59（Asia/Shanghai）',
    )
  })
})

describe('completed task editing', () => {
  it('已完成任务可以进入编辑表单，字段完整回填', () => {
    const completed = task({
      status: 'COMPLETED',
      completed_at: '2026-10-17T09:30:00+08:00',
    })

    const form = taskFormFrom(completed)

    expect(form.taskTitle).toBe(completed.task_title)
    expect(form.timePrecision).toBe('EXACT_DATETIME')
    expect(form.dueDate).toBe('2026-10-16')
    expect(buildUpdatePayload(form).due_time).toBe('17:00:00')
  })

  it('已完成任务表格提供编辑与删除入口（模板回归）', () => {
    const source = readFileSync(
      new URL('../src/views/DdlView.vue', import.meta.url),
      'utf8',
    )
    const completedSection = source.slice(source.indexOf('已完成（最近'))
    const operationColumn = completedSection.slice(0, completedSection.indexOf('</el-table>'))

    expect(operationColumn).toContain('openEditDialog(row)')
    expect(operationColumn).toContain('cancelTask(row)')
    expect(operationColumn).toContain('删除')
  })

  it('页面不再展示“提醒调度尚未接入”的过期文案', () => {
    const source = readFileSync(
      new URL('../src/views/DdlView.vue', import.meta.url),
      'utf8',
    )

    expect(source).not.toContain('提醒调度尚未接入')
    expect(source).not.toContain('尚未实现')
    expect(source).not.toContain('调度属 P8')
    expect(source).toContain('提醒调度已启用')
    expect(source).toContain('T-24h / T-6h / 逾期提醒范围')
  })
})


describe('manual create request-id lifecycle', () => {
  const form = (): DdlTaskFormState => ({
    ...emptyTaskForm(),
    courseId: '2001',
    courseName: '操作系统原理',
    taskTitle: '提交实验二报告',
    timePrecision: 'EXACT_DATETIME',
    dueDate: '2099-10-30',
    dueTime: '22:00:00',
  })

  it('首次提交使用打开弹窗时生成的请求 ID', () => {
    const operation = newManualCreateOperation('req_first')
    const prepared = manualCreateSubmitState(operation, form(), 'req_next')

    expect(prepared.requestId).toBe('req_first')
    expect(prepared.reused).toBe(false)
    expect(prepared.operation.submitted).toBe(true)
  })

  it('超时后未修改表单重试复用原请求 ID', () => {
    const first = manualCreateSubmitState(
      newManualCreateOperation('req_first'), form(), 'req_next')
    const retry = manualCreateSubmitState(first.operation, form(), 'req_next')

    expect(retry.requestId).toBe('req_first')
    expect(retry.reused).toBe(true)
  })

  it('超时后修改表单重试生成新的请求 ID', () => {
    const first = manualCreateSubmitState(
      newManualCreateOperation('req_first'), form(), 'req_next')
    const changed = { ...form(), taskTitle: '提交实验三报告' }
    const retry = manualCreateSubmitState(first.operation, changed, 'req_next')

    expect(retry.requestId).toBe('req_next')
    expect(retry.reused).toBe(false)
  })

  it('409 IDEMPOTENCY_CONFLICT 后重置为新的操作 ID', () => {
    const first = manualCreateSubmitState(
      newManualCreateOperation('req_first'), form(), 'req_next')
    const reset = resetManualCreateAfterConflict(first.operation, 'req_after_conflict')

    expect(reset.requestId).toBe('req_after_conflict')
    expect(reset.submitted).toBe(false)
    expect(reset.fingerprint).toBeNull()
  })

  it('buildUpdatePayload 默认不携带重新确认标记', () => {
    const payload = buildUpdatePayload(taskFormFrom(task()))
    expect(payload.reconfirm_time).toBe(false)

    const reconfirmed = buildUpdatePayload(taskFormFrom(task()), true)
    expect(reconfirmed.reconfirm_time).toBe(true)
  })
})

describe('edit dialog original time snapshot', () => {
  it('INVALID_OR_PAST 需要用户显式重新确认，并展示原精度/原因/时区', () => {
    const info = originalTimeInfo(task({
      time_precision: 'INVALID_OR_PAST',
      status: 'PENDING_CONFIRM',
      due_at: '2026-10-01T22:00:00+08:00',
      due_date: '2026-10-01',
      due_time: '22:00:00',
      time_ambiguity_reason: '时间被判定为非法或已过去，需人工确认',
    }))

    expect(info.precision).toBe('非法或已过期')
    expect(info.needsReconfirm).toBe(true)
    expect(info.timezone).toBe('Asia/Shanghai')
    expect(info.reason).toContain('需人工确认')
    expect(info.deadline).toBe('2026-10-01（周四）22:00（Asia/Shanghai）')
  })

  it('普通进行中任务不需要重新确认', () => {
    const info = originalTimeInfo(task())
    expect(info.needsReconfirm).toBe(false)
    expect(info.timezone).toBe('Asia/Shanghai')
  })
})


describe('board rendering states', () => {
  it('空看板返回四个空分组，计数全为 0', () => {
    const board = emptyBoard()
    expect(board.pending_inbox_tasks).toEqual([])
    expect(board.pending_change_drafts).toEqual([])
    expect(board.active_tasks).toEqual([])
    expect(board.completed_tasks).toEqual([])
    expect(boardCounts(board)).toEqual({
      pending: 0, drafts: 0, active: 0, overdue: 0, completed: 0,
    })
  })

  it('加载、空、错误三种状态在模板中可区分', () => {
    const source = readFileSync(
      new URL('../src/views/DdlView.vue', import.meta.url),
      'utf8',
    )
    expect(source).toContain('DDL 看板加载失败')
    expect(source).toContain('<el-skeleton')
    expect(source).toContain('<el-empty')
    expect(source).toContain(':description="loadError"')
  })
})
