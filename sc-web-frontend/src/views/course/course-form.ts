import type { ChangeEmbeddingModelRequest, CreateCourseRequest } from '@/api/course'
import type { EmbeddingProvider } from '@/api/user-config'

/** 课程表单纯逻辑；视图只负责渲染与调用 API。 */

export class CourseFormValidationError extends Error {}

export function toCreateCourseRequest(name: string, description: string): CreateCourseRequest {
  const courseName = name.trim()
  if (courseName === '') {
    throw new CourseFormValidationError('请填写课程名称。')
  }
  if (courseName === '未分类课程') {
    throw new CourseFormValidationError('“未分类课程”是系统默认课程保留名称，请更换课程名称。')
  }
  const trimmedDescription = description.trim()
  return trimmedDescription === ''
    ? { course_name: courseName }
    : { course_name: courseName, description: trimmedDescription }
}

export function toChangeEmbeddingRequest(
  useUserDefault: boolean,
  provider: EmbeddingProvider,
  model: string,
): ChangeEmbeddingModelRequest {
  if (useUserDefault) {
    return { use_user_default: true }
  }
  const embeddingModel = model.trim()
  if (embeddingModel === '') {
    throw new CourseFormValidationError('请填写新的 Embedding 模型名称。')
  }
  return {
    use_user_default: false,
    embedding_provider: provider,
    embedding_model: embeddingModel,
  }
}

export function indexStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '可用'
    case 'REBUILDING':
      return '重建中'
    case 'REBUILD_FAILED':
      return '重建失败'
    default:
      return status
  }
}
