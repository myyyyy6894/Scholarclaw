import { apiRequest } from '@/api/client'
import type { BigIntId } from '@/api/types'
import type { EmbeddingProvider } from '@/api/user-config'

/** 课程空间 API（`04` §4.3 `API-COURSE-001~004`）。 */

export type IndexStatus = 'ACTIVE' | 'REBUILDING' | 'REBUILD_FAILED'

export interface CourseNamespace {
  course_id: BigIntId
  course_name: string
  description: string | null
  is_default: boolean
  embedding_provider: EmbeddingProvider
  embedding_model: string
  embedding_dim: number
  index_version: number
  index_status: IndexStatus
  ready_doc_count: number
  parsing_doc_count: number
  failed_doc_count: number
  created_at: string
  updated_at: string
}

export interface CreateCourseRequest {
  course_name: string
  description?: string
}

export interface ChangeEmbeddingModelRequest {
  use_user_default: boolean
  embedding_provider?: EmbeddingProvider
  embedding_model?: string
}

export interface ChangeEmbeddingModelResult {
  course_id: BigIntId
  previous_index_version: number
  current_index_version: number
  index_status: IndexStatus
  embedding_provider: EmbeddingProvider
  embedding_model: string
  embedding_dim: number
  reindexing_doc_count: number
}

export interface DeleteCourseResult {
  deleted_course_id: BigIntId
}

export function listCourses(): Promise<CourseNamespace[]> {
  return apiRequest<CourseNamespace[]>('/v1/courses')
}

export function createCourse(request: CreateCourseRequest): Promise<CourseNamespace> {
  return apiRequest<CourseNamespace>('/v1/courses', { method: 'POST', body: request })
}

export function changeCourseEmbedding(
  courseId: BigIntId,
  request: ChangeEmbeddingModelRequest,
): Promise<ChangeEmbeddingModelResult> {
  return apiRequest<ChangeEmbeddingModelResult>(
    `/v1/courses/${encodeURIComponent(courseId)}/embedding-model`,
    { method: 'PUT', body: request },
  )
}

export function deleteCourse(courseId: BigIntId): Promise<DeleteCourseResult> {
  return apiRequest<DeleteCourseResult>(`/v1/courses/${encodeURIComponent(courseId)}`, {
    method: 'DELETE',
  })
}
