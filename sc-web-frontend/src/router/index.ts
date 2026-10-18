import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import { installAuthGuard } from '@/router/guard'
import HomeView from '@/views/HomeView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

/**
 * 路由表（`06` TASK-103）。
 *
 * 当前只落地骨架路由；`01` §6.1.2 要求的四个 MVP 视图由后续任务挂到同一套布局下：
 *
 * | 视图 | 落地任务 |
 * |---|---|
 * | 登录、注册与个人设置（BYOK） | TASK-205 |
 * | 课程知识库管理（左侧栏 + 文档抽屉） | TASK-404 |
 * | RAG 溯源问答主工作区（中间 Tab 1） | TASK-605 |
 * | DDL 任务看板与待确认收件箱（中间 Tab 2） | TASK-705 |
 *
 * 因此这里**不预先创建空视图组件**：空壳页面会让人误以为功能已存在（`05` §8.2）。
 * 需要登录的路由在接入时声明 `meta.requiresAuth = true`，由 guard 统一处理。
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
