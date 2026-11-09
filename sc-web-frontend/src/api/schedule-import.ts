import { apiRequest } from '@/api/client'
import type { RuleWrite } from '@/api/schedule'
import type { BigIntId } from '@/api/types'

export interface ScheduleSuggestion {
  source_text: string
  title: string | null
  teacher: string | null
  room: string | null
  weekday: number | null
  start_period: number | null
  end_period: number | null
  start_time: string | null
  end_time: string | null
  weeks: number[]
  issues: string[]
}
export interface ScheduleImportReceipt {
  term_id: BigIntId
  rule_ids: BigIntId[]
  schedule_version: number
}
export interface ScheduleImport {
  import_id: BigIntId
  term_id: BigIntId
  source_type: 'TEXT' | 'IMAGE'
  status: 'QUEUED' | 'RUNNING' | 'REVIEW_REQUIRED' | 'FAILED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELED'
  version: number
  attempt: number
  expires_at: string
  source_text: string | null
  draft: { rows: ScheduleSuggestion[] } | null
  error_message: string | null
  receipt: ScheduleImportReceipt | null
}
export interface ConfirmScheduleImport {
  version: number
  client_request_id: string
  rules: RuleWrite[]
}

const path = (id: BigIntId): string => `/v1/schedule/imports/${encodeURIComponent(id)}`
export const createTextScheduleImport = (termId: BigIntId, rawText: string, requestId: string): Promise<ScheduleImport> =>
  apiRequest('/v1/schedule/imports/text', { method: 'POST', body: { term_id: termId, raw_text: rawText, client_request_id: requestId } })
export const listScheduleImports = (termId: BigIntId): Promise<ScheduleImport[]> =>
  apiRequest(`/v1/schedule/imports/term/${encodeURIComponent(termId)}`)
export const getScheduleImport = (id: BigIntId): Promise<ScheduleImport> => apiRequest(path(id))
export const retryScheduleImport = (id: BigIntId, version: number): Promise<ScheduleImport> =>
  apiRequest(`${path(id)}/retry`, { method: 'POST', body: { version } })
export const cancelScheduleImport = (id: BigIntId, version: number): Promise<ScheduleImport> =>
  apiRequest(path(id), { method: 'DELETE', body: { version } })
export const confirmScheduleImport = (id: BigIntId, body: ConfirmScheduleImport): Promise<ScheduleImportReceipt> =>
  apiRequest(`${path(id)}/confirm`, { method: 'POST', body })
