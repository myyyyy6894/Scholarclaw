import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/stores/auth'
import { useCourseStore } from '@/stores/course'
import { browserStorage } from '@/utils/browser-storage'

const STORAGE_KEY = 'sc.auth.session'

/**
 * 登录态 store 测试。
 *
 * 重点验证两件事：会话可持久化并恢复；`user_id` 全程是字符串（`04` §2.6）。
 */
describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    browserStorage.remove(STORAGE_KEY)
  })

  it('初始状态为未登录', () => {
    const auth = useAuthStore()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.getToken()).toBeNull()
    expect(auth.displayName).toBe('未登录')
  })

  it('setSession 后进入已登录态并写入存储', () => {
    const auth = useAuthStore()

    auth.setSession({ token: 'jwt-token-value', user: { user_id: '10001', username: 'zhangsan_cs' } })

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.getToken()).toBe('jwt-token-value')
    expect(auth.displayName).toBe('zhangsan_cs')
    expect(browserStorage.read<{ token: string }>(STORAGE_KEY)?.token).toBe('jwt-token-value')
  })

  it('重新创建 store 时从存储恢复会话（模拟刷新页面）', () => {
    useAuthStore().setSession({
      token: 'jwt-token-value',
      user: { user_id: '10001', username: 'zhangsan_cs' },
    })

    setActivePinia(createPinia())
    const restored = useAuthStore()

    expect(restored.isAuthenticated).toBe(true)
    expect(restored.user?.username).toBe('zhangsan_cs')
  })

  it('user_id 保持十进制字符串，绝不被转成 Number', () => {
    const auth = useAuthStore()

    auth.setSession({ token: 't', user: { user_id: '9007199254740993', username: 'bigint_user' } })

    expect(typeof auth.user?.user_id).toBe('string')
    expect(auth.user?.user_id).toBe('9007199254740993')
  })

  it('clearSession 清空内存状态与存储', () => {
    const auth = useAuthStore()
    auth.setSession({ token: 'jwt-token-value', user: null })

    auth.clearSession()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
    expect(browserStorage.read(STORAGE_KEY)).toBeNull()
  })

  it('clearSession 同时清空课程上下文', () => {
    const auth = useAuthStore()
    const course = useCourseStore()
    auth.setSession({ token: 't', user: null })
    course.select('2001')

    auth.clearSession()

    expect(course.selectedCourseId).toBeNull()
  })

  it('存储内容损坏时按未登录处理，不抛异常', () => {
    browserStorage.write(STORAGE_KEY, 'not-an-object') // 存成字符串而非对象

    setActivePinia(createPinia())
    const auth = useAuthStore()

    expect(auth.isAuthenticated).toBe(false)
  })
})
