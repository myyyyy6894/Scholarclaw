import { apiImage, apiRequest } from '@/api/client'
import type { RuleWrite } from '@/api/schedule'
import type { BigIntId } from '@/api/types'

export interface ScheduleSuggestion {
  source_text: string
  source_ref?: string
  segment_id?: string
  section_code?: string | null
  state?: 'COURSE' | 'UNPARSED' | 'UNSCHEDULED'
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
export interface ScheduleRuleOrigin {
  rule_id: BigIntId
  segment_id: string | null
  source_ref: string | null
  section_code: string | null
}
export interface ScheduleImportReceipt {
  term_id: BigIntId
  rule_ids: BigIntId[]
  origins?: ScheduleRuleOrigin[]
  schedule_version: number
}
export interface ScheduleImport {
  import_id: BigIntId
  term_id: BigIntId
  source_type: 'TEXT' | 'IMAGE' | 'FILE'
  source_format?: 'XLSX' | 'CSV' | 'DOCX' | null
  file_assist_used?: boolean
  status: 'QUEUED' | 'RUNNING' | 'REVIEW_REQUIRED' | 'FAILED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELED'
  version: number
  schedule_version: number
  attempt: number
  expires_at: string
  source_text: string | null
  draft: { rows: ScheduleSuggestion[]; tables?: ScheduleImportTable[]; minimum_weeks?: number } | null
  error_message: string | null
  receipt: ScheduleImportReceipt | null
}
export interface ConfirmScheduleImport {
  version: number
  client_request_id: string
  rules: RuleWrite[]
  reviewed_schedule_version?: number
  conflict_token?: string
  source_segments?: Array<string | null>
}
export interface ScheduleImageModel {
  provider: string
  origin: string
  model_name: string
  confirmation_token: string
}

const path = (id: BigIntId): string => `/v1/schedule/imports/${encodeURIComponent(id)}`
export const createTextScheduleImport = (termId: BigIntId, rawText: string, requestId: string): Promise<ScheduleImport> =>
  apiRequest('/v1/schedule/imports/text', { method: 'POST', body: { term_id: termId, raw_text: rawText, client_request_id: requestId } })
export const getScheduleImageModel = (): Promise<ScheduleImageModel> => apiRequest('/v1/schedule/imports/image/model')
export function createImageScheduleImport(termId: BigIntId, image: File, requestId: string, consentToken: string): Promise<ScheduleImport> {
  const formData = new FormData()
  formData.append('term_id', termId)
  formData.append('client_request_id', requestId)
  formData.append('consent_token', consentToken)
  formData.append('confirm_external_transfer', 'true')
  formData.append('image', image)
  return apiRequest('/v1/schedule/imports/image', { method: 'POST', formData })
}
export const getScheduleImportImage = (id: BigIntId, signal?: AbortSignal): Promise<Blob> => apiImage(`${path(id)}/image`, signal)
export const retryImageScheduleImport = (id: BigIntId, version: number, consentToken: string): Promise<ScheduleImport> =>
  apiRequest(`${path(id)}/image/retry`, { method: 'POST', body: { version, consent_token: consentToken, confirm_external_transfer: true } })
export const listScheduleImports = (termId: BigIntId): Promise<ScheduleImport[]> =>
  apiRequest(`/v1/schedule/imports/term/${encodeURIComponent(termId)}`)
export const getScheduleImport = (id: BigIntId): Promise<ScheduleImport> => apiRequest(path(id))
export const retryScheduleImport = (id: BigIntId, version: number): Promise<ScheduleImport> =>
  apiRequest(`${path(id)}/retry`, { method: 'POST', body: { version } })
export const cancelScheduleImport = (id: BigIntId, version: number): Promise<ScheduleImport> =>
  apiRequest(path(id), { method: 'DELETE', body: { version } })
export const confirmScheduleImport = (id: BigIntId, body: ConfirmScheduleImport): Promise<ScheduleImportReceipt> =>
  apiRequest(`${path(id)}/confirm`, { method: 'POST', body })

export interface ScheduleImportTable {
  index: number
  label: string
  layout: 'GRID' | 'DETAIL' | 'UNKNOWN'
}
export interface ScheduleImportLayout {
  table_index: number
  header_row?: number
  mapping?: Record<string, number>
}
export interface ScheduleImportPreview {
  schedule_version: number
  conflict_token: string
  conflicts: { row: number; title: string; dates: string[]; duplicate: boolean }[]
}
export function createFileScheduleImport(termId: BigIntId, file: File, requestId: string): Promise<ScheduleImport> {
  const formData = new FormData()
  formData.append('term_id', termId)
  formData.append('client_request_id', requestId)
  formData.append('file', file)
  return apiRequest('/v1/schedule/imports/file', { method: 'POST', formData })
}
export function changeScheduleImportLayout(id: BigIntId, version: number, file: File,
  layout: ScheduleImportLayout): Promise<ScheduleImport> {
  const formData = new FormData()
  formData.append('version', String(version))
  formData.append('layout', new Blob([JSON.stringify(layout)], { type: 'application/json' }))
  formData.append('file', file)
  return apiRequest(`${path(id)}/file/layout`, { method: 'POST', formData })
}
export const getScheduleFileModel = (): Promise<ScheduleImageModel> => apiRequest('/v1/schedule/imports/file/model')
export const assistScheduleFile = (id: BigIntId, version: number, consentToken: string): Promise<ScheduleImport> =>
  apiRequest(`${path(id)}/file/assist`, {
    method: 'POST', body: { version, consent_token: consentToken, confirm_external_transfer: true },
  })
export const previewScheduleImport = (id: BigIntId, version: number, rules: RuleWrite[],
  sourceSegments: Array<string | null>): Promise<ScheduleImportPreview> =>
  apiRequest(`${path(id)}/preview`, { method: 'POST', body: { version, rules, source_segments: sourceSegments } })
