import { describe, expect, it } from 'vitest'
import type { RuleWrite, SchedulePeriod, ScheduleRule, ScheduleTerm } from '@/api/schedule'
import { editorToRule, importConflicts, operationFor, suggestionToEditor } from '@/views/schedule/schedule-import-form'

const term: ScheduleTerm = { term_id: '9007199254740993', name: '秋', first_monday: '2026-12-28', week_count: 16,
  timezone: 'Asia/Shanghai', active: true, version: 1, schedule_version: 1 }
const periods: SchedulePeriod[] = [{ period_no: 1, start_time: '08:00:00', end_time: '08:45:00' },
  { period_no: 2, start_time: '08:55:00', end_time: '09:40:00' }]
const existing: ScheduleRule = { rule_id: '9', term_id: term.term_id, course_id: null, title: '数学', teacher: null,
  room: null, weekday: 1, start_time: '08:00:00', end_time: '09:40:00', start_period: 1, end_period: 2, weeks: [1, 3], version: 1 }

describe('导入校对', () => {
  it('缺失内容不能默认为课程，明确填写后转换为独立课表规则', () => {
    const row = suggestionToEditor({ source_text: '待校对', title: null, teacher: null, room: null, weekday: null,
      start_period: null, end_period: null, start_time: null, end_time: null, weeks: [], issues: ['缺字段'] })
    expect(row.sourceText).toBe('待校对')
    expect(() => editorToRule(row, term)).toThrow('名称')
    Object.assign(row, { title: '物理', weekday: 1, weekText: '1-5单周', timeMode: 'period', startPeriod: 1, endPeriod: 2 })
    const rule = editorToRule(row, term)
    expect(rule.weeks).toEqual([1, 3, 5])
    expect(rule.course_id).toBeNull()
    expect(importConflicts([rule], [existing], periods)).toHaveLength(1)
  })
  it('相邻时间不算重叠，不同周也不算重叠', () => {
    const rule: RuleWrite = { term_id: term.term_id, course_id: null, title: '英语', teacher: null, room: null,
      weekday: 1, start_time: '09:40:00', end_time: '10:30:00', start_period: null, end_period: null,
      weeks: [1, 3], confirm_overlap: false }
    expect(importConflicts([rule], [existing], periods)).toEqual([])
    expect(importConflicts([{ ...rule, start_time: '08:00:00', weeks: [2] }], [existing], periods)).toEqual([])
  })
  it('不变的重试复用请求 ID，改动载荷生成新 ID', () => {
    const first = operationFor(null, 'a', () => 'id-1')
    expect(operationFor(first, 'a', () => 'id-2').requestId).toBe('id-1')
    expect(operationFor(first, 'b', () => 'id-2').requestId).toBe('id-2')
  })
})
