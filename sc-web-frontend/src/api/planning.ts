import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/** 所有分钟均为时区内自当天 00:00 起的分钟数；1440 仅用于结束时间。 */
export interface StudyWindow { weekday: number; start_minute: number; end_minute: number }
export interface PlanningPreference {
  timezone: string
  windows: StudyWindow[]
  focus_minutes: number
  daily_limit_minutes: number
  buffer_minutes: number
  version: number
}
export type PreferenceWrite = Omit<PlanningPreference, 'version'> & { version?: number | null }
export interface PersonalEvent {
  event_id: BigIntId
  title: string
  timezone: string
  start_date: string
  end_date: string | null
  weekday: number | null
  start_minute: number
  end_minute: number
  hard: boolean
  version: number
}
export type EventWrite = Omit<PersonalEvent, 'event_id' | 'version'> & { version?: number | null }
export interface TaskProfile {
  task_id: BigIntId
  estimated_minutes: number
  remaining_minutes: number
  priority: number
  earliest_start_at: string | null
  splittable: boolean
  min_block_minutes: number
  version: number
}
export type ProfileWrite = Omit<TaskProfile, 'task_id' | 'version'> & { version?: number | null }

const eventPath = (id: BigIntId) => `/v1/planning/events/${encodeURIComponent(id)}`
const taskPath = (id: BigIntId) => `/v1/planning/tasks/${encodeURIComponent(id)}`
export const getPlanningPreference = (): Promise<PlanningPreference | null> => apiRequest('/v1/planning/preferences')
export const savePlanningPreference = (body: PreferenceWrite): Promise<PlanningPreference> =>
  apiRequest('/v1/planning/preferences', { method: 'PUT', body })
export const listPersonalEvents = (): Promise<PersonalEvent[]> => apiRequest('/v1/planning/events')
export const createPersonalEvent = (body: EventWrite): Promise<PersonalEvent> =>
  apiRequest('/v1/planning/events', { method: 'POST', body })
export const updatePersonalEvent = (id: BigIntId, body: EventWrite): Promise<PersonalEvent> =>
  apiRequest(eventPath(id), { method: 'PUT', body })
export const deletePersonalEvent = (id: BigIntId, version: number): Promise<null> =>
  apiRequest(`${eventPath(id)}?version=${version}`, { method: 'DELETE' })
export const listTaskProfiles = (): Promise<TaskProfile[]> => apiRequest('/v1/planning/tasks')
export const saveTaskProfile = (id: BigIntId, body: ProfileWrite): Promise<TaskProfile> =>
  apiRequest(taskPath(id), { method: 'PUT', body })
export const deleteTaskProfile = (id: BigIntId, version: number): Promise<null> =>
  apiRequest(`${taskPath(id)}?version=${version}`, { method: 'DELETE' })

export interface StudyPlanBlock {
  block_id: BigIntId
  task_id: BigIntId
  title: string
  start_at: string
  end_at: string
  state: 'PROPOSED' | 'PLANNED' | 'DONE' | 'CANCELED'
  locked: boolean
  overdue: boolean
  version: number
}
export interface StudyPlanSummary {
  plan_id: BigIntId
  from_date: string
  to_date: string
  timezone: string
  state: 'DRAFT' | 'ACCEPTED' | 'CANCELED'
  revision: number
  created_at: string
}
export interface StudyPlan extends Omit<StudyPlanSummary, 'created_at'> {
  stale: boolean
  warnings: string[]
  shortages: Array<{ task_id: BigIntId; title: string; missing_minutes: number; reason: string; overdue: boolean }>
  unavailable_tasks: Array<{ task_id: BigIntId; title: string; reason: string }>
  blocks: StudyPlanBlock[]
}
export interface CreateStudyPlan {
  from_date: string
  to_date: string
  client_request_id: string
  confirm_no_active_term: boolean
}
const planPath = (id: BigIntId) => `/v1/planning/plans/${encodeURIComponent(id)}`
export const createStudyPlan = (body: CreateStudyPlan): Promise<StudyPlan> =>
  apiRequest('/v1/planning/plans', { method: 'POST', body })
export const listStudyPlans = (): Promise<StudyPlanSummary[]> => apiRequest('/v1/planning/plans')
export const getStudyPlan = (id: BigIntId): Promise<StudyPlan> => apiRequest(planPath(id))
export const acceptStudyPlan = (id: BigIntId, revision: number, clientRequestId: string, blockIds: BigIntId[]): Promise<StudyPlan> =>
  apiRequest(`${planPath(id)}/accept`, { method: 'POST', body: { revision, client_request_id: clientRequestId, block_ids: blockIds } })
export const cancelStudyPlan = (id: BigIntId, revision: number): Promise<null> =>
  apiRequest(`${planPath(id)}?revision=${revision}`, { method: 'DELETE' })
