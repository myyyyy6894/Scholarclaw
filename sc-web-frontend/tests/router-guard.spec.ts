import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent } from 'vue'

import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type RouteRecordRaw, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { installAuthGuard, LOGIN_ROUTE_NAME } from '@/router/guard'
import { useAuthStore } from '@/stores/auth'
import { browserStorage } from '@/utils/browser-storage'

const AppShellStub = defineComponent({ name: 'AppShellStub', template: '<router-view />' })
const ViewStub = defineComponent({ template: '<div />' })

vi.mock('@/components/layout/AppShell.vue', () => ({ default: AppShellStub }))
vi.mock('@/views/CoursesView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/DdlView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/GuideView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/HomeView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/KnowledgeView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/LoginView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/NotFoundView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/PlanningView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/RagView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/ScheduleView.vue', () => ({ default: ViewStub }))
vi.mock('@/views/SettingsView.vue', () => ({ default: ViewStub }))

const { routes: appRoutes } = await import('@/router/routes')

/**
 * 鉴权守卫与真实路由表测试（`06` TASK-103）。
 *
 * 同时锁定真实 `routes` 结构的根布局挂载（直接挂载 `AppShell`）、子路由名完整性、
 * 未登录跳转 `/login` 并带 `redirect`、已登录放行与 `guestOnly` 重定向行为。
 */
function buildRouter(): Router {
  const routes: RouteRecordRaw[] = [
    { path: '/', name: 'home', component: ViewStub },
    { path: '/login', name: LOGIN_ROUTE_NAME, component: ViewStub, meta: { guestOnly: true } },
    { path: '/settings', name: 'settings', component: ViewStub, meta: { requiresAuth: true } },
    { path: '/workspace', name: 'workspace', component: ViewStub, meta: { requiresAuth: true } },
  ]
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  return router
}

function buildAppRouter(): Router {
  const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
  installAuthGuard(router)
  return router
}

describe('installAuthGuard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    browserStorage.remove('sc.auth.session')
  })

  it('公开路由无需登录即可访问', async () => {
    const router = buildRouter()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('未登录访问受保护路由会重定向到登录页并保留原始目标', async () => {
    const router = buildRouter()

    await router.push('/workspace')

    expect(router.currentRoute.value.name).toBe(LOGIN_ROUTE_NAME)
    expect(router.currentRoute.value.query.redirect).toBe('/workspace')
  })

  it('已登录访问受保护路由直接放行', async () => {
    useAuthStore().setSession({ token: 'jwt-token-value', user: null })
    const router = buildRouter()

    await router.push('/workspace')

    expect(router.currentRoute.value.name).toBe('workspace')
  })

  it('登录后重定向参数可用于回跳（TASK-205 使用）', async () => {
    useAuthStore().setSession({ token: 'jwt-token-value', user: null })
    const router = buildRouter()

    await router.push({ name: LOGIN_ROUTE_NAME, query: { redirect: '/workspace' } })
    await router.push('/workspace')

    expect(router.currentRoute.value.fullPath).toBe('/workspace')
  })

  it('已登录访问登录页会回到设置页，避免重复登录', async () => {
    useAuthStore().setSession({ token: 'jwt-token-value', user: null })
    const router = buildRouter()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('settings')
  })

  it('未登录访问登录页正常放行', async () => {
    const router = buildRouter()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe(LOGIN_ROUTE_NAME)
  })
})

describe('app routes', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    browserStorage.remove('sc.auth.session')
  })

  it('根路由直接挂载 AppShell 且保留全部子路由与 404 路由名称', () => {
    const routesSource = readFileSync(resolve(process.cwd(), 'src/router/routes.ts'), 'utf8')
    expect(routesSource).toContain("import AppShell from '@/components/layout/AppShell.vue'")
    expect(routesSource).not.toContain('AppLayout')

    expect(appRoutes[0]?.path).toBe('/')
    expect(appRoutes[0]?.component).toBe(AppShellStub)

    const childNames = (appRoutes[0]?.children ?? []).map((route) => route.name)
    expect(childNames).toEqual([
      'home',
      'login',
      'courses',
      'knowledge',
      'rag',
      'ddl',
      'schedule',
      'planning',
      'settings',
      'guide',
    ])
    expect(appRoutes[1]?.name).toBe('not-found')
  })

  it('真实路由表对受保护子路由、公开路由与登录页执行守卫与导航', async () => {
    const router = buildAppRouter()

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')

    await router.push('/guide')
    expect(router.currentRoute.value.name).toBe('guide')

    await router.push('/schedule')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/schedule')

    useAuthStore().setSession({ token: 'jwt-token-value', user: null })
    await router.push('/planning')
    expect(router.currentRoute.value.name).toBe('planning')

    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('settings')
  })
})
