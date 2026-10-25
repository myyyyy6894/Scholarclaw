import { describe, expect, it } from 'vitest'

import { IMPLEMENTED_CAPABILITIES, UPCOMING_TASKS } from '@/views/home/home-tasks'

/** 首页只能把真正未实现的任务列在 upcoming，已实现任务不得重复出现。 */
describe('home task lists', () => {
  it('TASK-404 已实现且不再标记为 upcoming', () => {
    expect(IMPLEMENTED_CAPABILITIES.some((item) => item.includes('TASK-404'))).toBe(true)
    expect(UPCOMING_TASKS.some((item) => item.task === 'TASK-404')).toBe(false)
  })

  it('TASK-705 已实现且不再标记为 upcoming', () => {
    expect(IMPLEMENTED_CAPABILITIES.some((item) => item.includes('TASK-705'))).toBe(true)
    expect(UPCOMING_TASKS.some((item) => item.task === 'TASK-705')).toBe(false)
  })

  it('upcoming 只保留确实未实现的任务', () => {
    expect(UPCOMING_TASKS.map((item) => item.task)).toEqual(['TASK-706'])
  })

  it('已实现清单与 upcoming 任务号不重叠', () => {
    const implementedTasks = IMPLEMENTED_CAPABILITIES.flatMap((item) =>
      [...item.matchAll(/TASK-\d+/g)].map((match) => match[0]),
    )
    const upcomingTasks = UPCOMING_TASKS.map((item) => item.task)
    expect(implementedTasks.filter((task) => upcomingTasks.includes(task))).toEqual([])
  })
})
