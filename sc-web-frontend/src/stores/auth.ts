import { computed, ref } from 'vue'

import { defineStore } from 'pinia'

import type { BigIntId } from '@/api/types'
import { useCourseStore } from '@/stores/course'
import { browserStorage } from '@/utils/browser-storage'

/**
 * 登录态管理（`06` TASK-103「建立鉴权状态管理」）。
 *
 * 边界说明：
 *
 * * `user_id` 声明为 `BigIntId`（十进制字符串），遵循 `04` §2.6 的 `BIGINT` 铁律；
 * * 本 store 只负责**保存与读取**会话，真正的登录/登出接口调用属 TASK-205（登录页与个人设置）；
 * * **前端登录态不是权限边界**（`05` §4.4）：所有授权判断仍由 Java 后端按其
 *   `SecurityContext` 执行，路由守卫只是防止未登录用户看到空白页面。
 */

export interface AuthUser {
  user_id: BigIntId
  username: string
}

export interface AuthSession {
  token: string
  user: AuthUser | null
}

const STORAGE_KEY = 'sc.auth.session'

export const useAuthStore = defineStore('auth', () => {
  const restored = browserStorage.read<AuthSession>(STORAGE_KEY)

  const token = ref<string | null>(restored?.token ?? null)
  const user = ref<AuthUser | null>(restored?.user ?? null)

  const isAuthenticated = computed(() => token.value !== null)
  const displayName = computed(() => user.value?.username ?? '未登录')

  function setSession(session: AuthSession): void {
    token.value = session.token
    user.value = session.user
    browserStorage.write(STORAGE_KEY, session)
  }

  function clearSession(): void {
    token.value = null
    user.value = null
    browserStorage.remove(STORAGE_KEY)
    // 所有会话清理路径（登出、401、注销）都统一清空课程上下文
    useCourseStore().clear()
  }

  /** 供 API client 注入的取令牌入口。 */
  function getToken(): string | null {
    return token.value
  }

  return { token, user, isAuthenticated, displayName, setSession, clearSession, getToken }
})
