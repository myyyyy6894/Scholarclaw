import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import { installAuthGuard } from '@/router/guard'
import CoursesView from '@/views/CoursesView.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import SettingsView from '@/views/SettingsView.vue'

/**
 * 路由表（`06` TASK-103 / TASK-205）。
 *
 * | 视图 | 落地任务 |
 * |---|---|
 * | 登录、注册与个人设置（BYOK） | TASK-205（已落地） |
 * | 课程知识库管理（左侧栏 + 文档抽屉） | TASK-404 |
 * | RAG 溯源问答主工作区（中间 Tab 1） | TASK-605 |
 * | DDL 任务看板与待确认收件箱（中间 Tab 2） | TASK-705 |
 *
 * 需要登录的路由声明 `meta.requiresAuth = true`；登录页声明 `meta.guestOnly = true`，
 * 已登录用户访问时直接回到设置页，避免出现"已登录还能看到登录表单"的割裂状态。
 */

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AppLayout,
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
        path: 'settings',
        name: 'settings',
        component: SettingsView,
        meta: { title: '个人设置', requiresAuth: true },
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

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

installAuthGuard(router)
