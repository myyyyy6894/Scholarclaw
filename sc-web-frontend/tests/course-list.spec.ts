import { describe, expect, it } from 'vitest'

import type { CourseNamespace } from '@/api/course'
import { filterCourses } from '@/views/course/course-list'

function course(name: string, description: string | null): CourseNamespace {
  return {
    course_id: name,
    course_name: name,
    description,
    is_default: false,
    embedding_provider: 'OPENAI_COMPATIBLE',
    embedding_model: 'text-embedding-3-small',
    embedding_dim: 1536,
    index_version: 1,
    index_status: 'ACTIVE',
    ready_doc_count: 0,
    parsing_doc_count: 0,
    failed_doc_count: 0,
    created_at: '2026-10-01T10:00:00+08:00',
    updated_at: '2026-10-01T10:00:00+08:00',
  }
}

const COURSES = [
  course('数据结构与算法分析', '2025-2026 学年 第 1 学期'),
  course('计算机网络', 'Computer Networks'),
  course('操作系统', null),
]

describe('course filter', () => {
  it('空查询返回全部课程', () => {
    expect(filterCourses(COURSES, '')).toHaveLength(3)
    expect(filterCourses(COURSES, '   ')).toHaveLength(3)
  })

  it('按课程名/备注大小写不敏感过滤', () => {
    expect(filterCourses(COURSES, '网络').map((item) => item.course_name)).toEqual([
      '计算机网络',
    ])
    expect(filterCourses(COURSES, 'computer').map((item) => item.course_name)).toEqual([
      '计算机网络',
    ])
    expect(filterCourses(COURSES, '2025-2026')).toHaveLength(1)
  })

  it('无匹配返回空数组，且不修改原数组', () => {
    expect(filterCourses(COURSES, '不存在')).toEqual([])
    expect(COURSES).toHaveLength(3)
  })
})
