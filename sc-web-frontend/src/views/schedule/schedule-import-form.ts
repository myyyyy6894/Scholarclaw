import type { RuleWrite, SchedulePeriod, ScheduleRule, ScheduleTerm } from '@/api/schedule'
import type { ScheduleSuggestion } from '@/api/schedule-import'
import { parseWeeks } from '@/views/schedule/schedule-date'

export interface ImportEditor {
  included: boolean
  sourceText: string
  issues: string[]
  title: string
  teacher: string
  room: string
  weekday: number | undefined
  weekText: string
  timeMode: 'time' | 'period'
  startTime: string
  endTime: string
  startPeriod: number | undefined
  endPeriod: number | undefined
}

export function suggestionToEditor(row: ScheduleSuggestion): ImportEditor {
  return {
    included: true, sourceText: row.source_text, issues: row.issues,
    title: row.title ?? '', teacher: row.teacher ?? '', room: row.room ?? '', weekday: row.weekday ?? undefined,
    weekText: row.weeks.join(','), timeMode: row.start_period !== null ? 'period' : 'time',
    startTime: row.start_time ?? '', endTime: row.end_time ?? '',
    startPeriod: row.start_period ?? undefined, endPeriod: row.end_period ?? undefined,
  }
}

export function editorToRule(editor: ImportEditor, term: ScheduleTerm): RuleWrite {
  if (!editor.title.trim()) throw new Error('请填写每条选中课程的名称。')
  if (editor.weekday === undefined || editor.weekday < 1 || editor.weekday > 7) throw new Error('请选择每条选中课程的星期。')
  const weeks = parseWeeks(editor.weekText, term.week_count)
  if (editor.timeMode === 'period' && (editor.startPeriod === undefined || editor.endPeriod === undefined
      || editor.startPeriod < 1 || editor.endPeriod > 30 || editor.endPeriod < editor.startPeriod)) {
    throw new Error('请填写有效的开始和结束节次。')
  }
  if (editor.timeMode === 'time' && (!editor.startTime || !editor.endTime)) throw new Error('请填写课程开始和结束时间。')
  return {
    term_id: term.term_id, course_id: null, title: editor.title.trim(), teacher: editor.teacher.trim() || null,
    room: editor.room.trim() || null, weekday: editor.weekday, weeks,
    start_time: editor.timeMode === 'time' ? editor.startTime : null,
    end_time: editor.timeMode === 'time' ? editor.endTime : null,
    start_period: editor.timeMode === 'period' ? editor.startPeriod ?? null : null,
    end_period: editor.timeMode === 'period' ? editor.endPeriod ?? null : null,
    confirm_overlap: false,
  }
}

function seconds(value: string | null): number | null {
  if (!value || !/^\d{2}:\d{2}(?::\d{2})?$/.test(value)) return null
  const [hour, minute, second = 0] = value.split(':').map(Number)
  if (hour > 23 || minute > 59 || second > 59) return null
  return hour * 3600 + minute * 60 + second
}
function times(rule: RuleWrite | ScheduleRule, periods: SchedulePeriod[]): [number, number] | null {
  const start = rule.start_period === null ? rule.start_time : periods.find((item) => item.period_no === rule.start_period)?.start_time ?? null
  const end = rule.end_period === null ? rule.end_time : periods.find((item) => item.period_no === rule.end_period)?.end_time ?? null
  const a = seconds(start)
  const b = seconds(end)
  return a !== null && b !== null && a < b ? [a, b] : null
}
function overlaps(a: RuleWrite | ScheduleRule, b: RuleWrite | ScheduleRule, periods: SchedulePeriod[]): boolean {
  if (a.weekday !== b.weekday || !a.weeks.some((week) => b.weeks.includes(week))) return false
  const left = times(a, periods)
  const right = times(b, periods)
  return left !== null && right !== null && left[0] < right[1] && right[0] < left[1]
}

/** UI 提前提示；服务端仍会按全部实际发生实例再次检查。 */
export function importConflicts(selected: RuleWrite[], existing: ScheduleRule[], periods: SchedulePeriod[]): string[] {
  const messages: string[] = []
  selected.forEach((rule, index) => {
    const previous = [...existing, ...selected.slice(0, index)]
    for (const other of previous) {
      if (overlaps(rule, other, periods)) {
        const kind = rule.title.trim() === other.title.trim() ? '可能重复或时间重叠' : '时间重叠'
        messages.push(`「${rule.title}」与「${other.title}」${kind}`)
      }
    }
  })
  return [...new Set(messages)]
}

export interface ImportOperation { fingerprint: string; requestId: string }
/** 同一请求的网络重试保留 ID；载荷改变则生成新的 ID。 */
export function operationFor(previous: ImportOperation | null, fingerprint: string, createId: () => string): ImportOperation {
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, requestId: createId() }
}
