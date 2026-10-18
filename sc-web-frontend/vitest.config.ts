import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'

/**
 * 单元测试只覆盖与协议/状态相关的纯逻辑（统一响应信封解析、错误映射、鉴权状态、路由守卫），
 * 因此使用 `node` 环境即可，不需要 jsdom／真实浏览器：组件级与端到端验证留给后续页面任务。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.spec.ts'],
  },
})
