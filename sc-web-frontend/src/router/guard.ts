import type { Router } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 鉴权守卫（`06` TASK-103「建立鉴权状态管理」）。
 *
 * 与路由表分开成独立模块，原因很具体：路由表用 `createWebHistory` 创建，依赖浏览器 `window`，
 * 而守卫本身是纯逻辑，应当能在 node 环境下直接单测。分开后测试只 import 本模块即可。
 *
 * **前端守卫不是权限边界**（`05` §4.4）：它只避免未登录用户看到空白页面，
 * 所有资源归属与授权判定仍由 Java 后端按其 `SecurityContext` 执行。
 */

/** 登录路由名。登录页由 TASK-205 落地，此处先固定重定向目标，避免字符串散落各处。 */
export const LOGIN_ROUTE_NAME = 'login'

export function installAuthGuard(target: Router): void {
  target.beforeEach((to) => {
    if (to.meta.requiresAuth !== true) {
      return true
    }
    if (useAuthStore().isAuthenticated) {
      return true
    }
    return { name: LOGIN_ROUTE_NAME, query: { redirect: to.fullPath } }
  })
}
