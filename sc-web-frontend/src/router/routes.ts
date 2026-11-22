import type { RouteRecordRaw } from 'vue-router'

import AppShell from '@/components/layout/AppShell.vue'
import CoursesView from '@/views/CoursesView.vue'
import DdlView from '@/views/DdlView.vue'
import GuideView from '@/views/GuideView.vue'
import HomeView from '@/views/HomeView.vue'
import KnowledgeView from '@/views/KnowledgeView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import PlanningView from '@/views/PlanningView.vue'
import RagView from '@/views/RagView.vue'
import ScheduleView from '@/views/ScheduleView.vue'
import SettingsView from '@/views/SettingsView.vue'

/**
 * 路由表。
 *
 * 需要登录的路由声明 `meta.requiresAuth = true`；登录页声明 `meta.guestOnly = true`，
 * 已登录用户访问时直接回到设置页，避免出现"已登录还能看到登录表单"的割裂状态。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AppShell,
    children: [
      {
        path: '',
        name: 'home',
        component: HomeView,
        meta: { title: '工程骨架' },
      },
      {
        path: 'login',
        name: 'login',
        component: LoginView,
        meta: { title: '登录', guestOnly: true },
      },
      {
        path: 'courses',
        name: 'courses',
        component: CoursesView,
        meta: { title: '课程空间', requiresAuth: true },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: KnowledgeView,
        meta: { title: '课程知识库', requiresAuth: true },
      },
      {
        path: 'rag',
        name: 'rag',
        component: RagView,
        meta: { title: '课程问答', requiresAuth: true },
      },
      {
        path: 'ddl',
        name: 'ddl',
        component: DdlView,
        meta: { title: 'DDL 待办', requiresAuth: true },
      },
      {
        path: 'schedule',
        name: 'schedule',
        component: ScheduleView,
        meta: { title: '我的课表', requiresAuth: true },
      },
      {
        path: 'planning',
        name: 'planning',
        component: PlanningView,
        meta: { title: '时间规划', requiresAuth: true },
      },
      {
        path: 'settings',
        name: 'settings',
        component: SettingsView,
        meta: { title: '个人设置', requiresAuth: true },
      },
      {
        // 使用指南：不要求登录，未注册的人也能先看功能说明
        path: 'guide',
        name: 'guide',
        component: GuideView,
        meta: { title: '使用指南' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
    meta: { title: '页面不存在' },
  },
]
