import { describe, expect, it } from 'vitest'

import {
  CourseFormValidationError,
  indexStatusLabel,
  toChangeEmbeddingRequest,
  toCreateCourseRequest,
} from '@/views/course/course-form'

describe('course form', () => {
  it('创建课程时 trim 名称与备注，空备注整个省略', () => {
    expect(toCreateCourseRequest('  操作系统原理  ', '  2026 秋  ')).toEqual({
      course_name: '操作系统原理',
      description: '2026 秋',
    })
    expect(toCreateCourseRequest('数据库', '   ')).toEqual({ course_name: '数据库' })
  })

  it('拒绝空名称与系统保留名', () => {
    expect(() => toCreateCourseRequest('   ', '')).toThrow(CourseFormValidationError)
    expect(() => toCreateCourseRequest('未分类课程', '')).toThrow(CourseFormValidationError)
  })

  it('使用个人默认模型时只提交 use_user_default', () => {
    expect(toChangeEmbeddingRequest(true, 'ZHIPU', 'ignored')).toEqual({
      use_user_default: true,
    })
  })

  it('指定模型时要求模型名称并保留供应商', () => {
    expect(toChangeEmbeddingRequest(false, 'OLLAMA', ' bge-m3 ')).toEqual({
      use_user_default: false,
      embedding_provider: 'OLLAMA',
      embedding_model: 'bge-m3',
    })
    expect(() => toChangeEmbeddingRequest(false, 'OLLAMA', '   ')).toThrow(
      CourseFormValidationError,
    )
  })

  it('索引状态映射为可读文案', () => {
    expect(indexStatusLabel('ACTIVE')).toBe('可用')
    expect(indexStatusLabel('REBUILDING')).toBe('重建中')
    expect(indexStatusLabel('REBUILD_FAILED')).toBe('重建失败')
    expect(indexStatusLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})
