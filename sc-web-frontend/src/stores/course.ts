import { defineStore } from 'pinia'

import type { BigIntId } from '@/api/types'

/**
 * 当前课程上下文（`06` TASK-404）。
 *
 * 课程切换在知识库页与后续 RAG 问答之间共享；只保存课程 ID（非敏感），
 * 不写入任何密钥或用户内容。页面刷新后由知识库页重新选择第一门课程。
 */
export const useCourseStore = defineStore('course', {
  state: () => ({
    selectedCourseId: null as BigIntId | null,
  }),
  actions: {
    select(courseId: BigIntId | null): void {
      this.selectedCourseId = courseId
    },
    clear(): void {
      this.selectedCourseId = null
    },
  },
})
