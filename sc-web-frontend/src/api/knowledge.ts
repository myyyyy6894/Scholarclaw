import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'

/** 课件文档 API（`04` §4.4 `API-DOC-001~005`）。 */

export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'READY' | 'FAILED' | 'DELETING' | 'DELETED'
export type ListableDocumentStatus = DocumentStatus

export interface KnowledgeDoc {
  doc_id: BigIntId
  course_id: BigIntId
  doc_name: string
  file_ext: string
  file_size_bytes: number
  status: DocumentStatus
  parse_version: number
  course_index_version: number
  indexed_embedding_model: string | null
  indexed_embedding_dim: number | null
  page_count: number
  chunk_count: number
  retry_count: number
  last_ingest_job_id: string | null
  error_code: string | null
  error_msg: string | null
  enqueued_at: string | null
  ready_at: string | null
  created_at: string
  updated_at: string
}

export interface DeleteDocResult {
  deleted_doc_id: BigIntId
  status: DocumentStatus
}

export function listDocs(courseId: BigIntId, status?: ListableDocumentStatus): Promise<KnowledgeDoc[]> {
  const query = status === undefined ? '' : `?status=${encodeURIComponent(status)}`
  return apiRequest<KnowledgeDoc[]>(
    `/v1/courses/${encodeURIComponent(courseId)}/docs${query}`,
  )
}

export function uploadDoc(courseId: BigIntId, file: File): Promise<KnowledgeDoc> {
  const form = new FormData()
  form.append('file', file)
  return apiRequest<KnowledgeDoc>(
    `/v1/courses/${encodeURIComponent(courseId)}/docs/upload`,
    { method: 'POST', formData: form },
  )
}

export function deleteDoc(docId: BigIntId): Promise<DeleteDocResult> {
  return apiRequest<DeleteDocResult>(`/v1/docs/${encodeURIComponent(docId)}`, {
    method: 'DELETE',
  })
}

/** 失败文档重新入队（`API-DOC-003`）。 */
export function retryDoc(docId: BigIntId): Promise<KnowledgeDoc> {
  return apiRequest<KnowledgeDoc>(`/v1/docs/${encodeURIComponent(docId)}/retry`, {
    method: 'POST',
  })
}

/** READY 文档重建索引（`API-DOC-004`）。 */
export function reindexDoc(docId: BigIntId): Promise<KnowledgeDoc> {
  return apiRequest<KnowledgeDoc>(`/v1/docs/${encodeURIComponent(docId)}/reindex`, {
    method: 'POST',
  })
}
