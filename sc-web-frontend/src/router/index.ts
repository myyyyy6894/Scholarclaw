import { createRouter, createWebHistory } from 'vue-router'

import { installAuthGuard } from '@/router/guard'
import { routes } from '@/router/routes'

export { routes }

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

installAuthGuard(router)
