import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/** 课表是独立领域；所有日期/时间按学期时区解释，不要求课程知识库。 */
export interface ScheduleTerm {
  term_id: BigIntId
  name: string
  first_monday: string
  week_count: number
  timezone: string
  active: boolean
  version: number
  schedule_version: number
}
export interface TermWrite {
  name: string
  first_monday: string
  week_count: number
  timezone: string
  active: boolean
  version?: number
  confirm_schedule_change?: boolean
}
export interface SchedulePeriod {
  period_no: number
  start_time: string
  end_time: string
}
export interface PeriodImpact {
  rule_id: BigIntId
  title: string
  old_start: string
  old_end: string
  new_start: string
  new_end: string
}
export interface PeriodsWrite {
  version: number
  periods: SchedulePeriod[]
  confirm_affected: boolean
}
export interface ScheduleRule {
  rule_id: BigIntId
  term_id: BigIntId
  course_id: BigIntId | null
  title: string
  teacher: string | null
  room: string | null
  weekday: number
  start_time: string
  end_time: string
  start_period: number | null
  end_period: number | null
  weeks: number[]
  version: number
}
export interface RuleWrite {
  term_id: BigIntId
  course_id: BigIntId | null
  title: string
  teacher: string | null
  room: string | null
  weekday: number
  start_time: string | null
  end_time: string | null
  start_period: number | null
  end_period: number | null
  weeks: number[]
  version?: number
  confirm_overlap: boolean
}
export interface ScheduleException {
  exception_id: BigIntId
  rule_id: BigIntId
  original_date: string
  kind: 'CANCEL' | 'MOVE'
  target_date: string | null
  start_time: string | null
  end_time: string | null
  room: string | null
  version: number
}
export interface ExceptionWrite {
  kind: 'CANCEL' | 'MOVE'
  target_date: string | null
  start_time: string | null
  end_time: string | null
  room: string | null
  version?: number
  confirm_overlap: boolean
}
export interface ScheduleOccurrence {
  key: string
  rule_id: BigIntId
  title: string
  teacher: string | null
  room: string | null
  original_date: string
  date: string
  start_at: string
  end_at: string
  start_period: number | null
  end_period: number | null
  original_week: number
  actual_week: number | null
  moved: boolean
  conflict: boolean
}
export interface OccurrencesResult {
  term_id: BigIntId
  timezone: string
  schedule_version: number
  week_no: number | null
  occurrences: ScheduleOccurrence[]
}

const termPath = (id: BigIntId) => `/v1/schedule/terms/${encodeURIComponent(id)}`
const rulePath = (id: BigIntId) => `/v1/schedule/rules/${encodeURIComponent(id)}`

export const listScheduleTerms = (): Promise<ScheduleTerm[]> => apiRequest('/v1/schedule/terms')
export const createScheduleTerm = (body: TermWrite): Promise<ScheduleTerm> =>
  apiRequest('/v1/schedule/terms', { method: 'POST', body })
export const updateScheduleTerm = (id: BigIntId, body: TermWrite): Promise<ScheduleTerm> =>
  apiRequest(termPath(id), { method: 'PUT', body })
export const listSchedulePeriods = (id: BigIntId): Promise<SchedulePeriod[]> => apiRequest(`${termPath(id)}/periods`)
export const previewSchedulePeriods = (id: BigIntId, body: PeriodsWrite): Promise<{ affected: PeriodImpact[] }> =>
  apiRequest(`${termPath(id)}/periods/preview`, { method: 'POST', body })
export const saveSchedulePeriods = (id: BigIntId, body: PeriodsWrite): Promise<{ affected: PeriodImpact[] }> =>
  apiRequest(`${termPath(id)}/periods`, { method: 'PUT', body })
export const listScheduleRules = (id: BigIntId): Promise<ScheduleRule[]> => apiRequest(`${termPath(id)}/rules`)
export const createScheduleRule = (body: RuleWrite): Promise<ScheduleRule> =>
  apiRequest('/v1/schedule/rules', { method: 'POST', body })
export const updateScheduleRule = (id: BigIntId, body: RuleWrite): Promise<ScheduleRule> =>
  apiRequest(rulePath(id), { method: 'PUT', body })
export const deleteScheduleRule = (id: BigIntId, version: number): Promise<null> =>
  apiRequest(`${rulePath(id)}?version=${version}`, { method: 'DELETE' })
export const listScheduleExceptions = (id: BigIntId): Promise<ScheduleException[]> =>
  apiRequest(`${rulePath(id)}/exceptions`)
export const saveScheduleException = (id: BigIntId, originalDate: string, body: ExceptionWrite): Promise<ScheduleException> =>
  apiRequest(`${rulePath(id)}/exceptions/${encodeURIComponent(originalDate)}`, { method: 'PUT', body })
export const deleteScheduleException = (id: BigIntId, originalDate: string, version: number): Promise<null> =>
  apiRequest(`${rulePath(id)}/exceptions/${encodeURIComponent(originalDate)}?version=${version}`, { method: 'DELETE' })
export const listOccurrences = (termId: BigIntId, from: string, to: string): Promise<OccurrencesResult> => {
  const query = new URLSearchParams({ term_id: termId, from, to })
  return apiRequest(`/v1/schedule/occurrences?${query}`)
}

export interface ScheduleReminderPreference {
  enabled: boolean
  version: number
  delivery: 'WEB_QQ' | 'WEB_ONLY_DISABLED' | 'WEB_ONLY_UNVERIFIED'
}
const scheduleReminderPath = '/v1/schedule/reminder-preference'
export const getScheduleReminderPreference = (): Promise<ScheduleReminderPreference> =>
  apiRequest(scheduleReminderPath)
export const setScheduleReminderPreference = (enabled: boolean, version: number): Promise<ScheduleReminderPreference> =>
  apiRequest(scheduleReminderPath, { method: 'PUT', body: { enabled, version } })
