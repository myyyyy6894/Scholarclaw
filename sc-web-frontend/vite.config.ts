import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

/**
 * 开发服务器刻意只绑定环回地址（`ENVIRONMENT.md` ENV-04 的教训：不要把未加固的服务暴露到局域网），
 * 并把 `/api` 与 `/webhooks` 代理到 Java 后端，避免前端为了联调而硬编码后端地址。
 *
 * 生产构建产物由 TASK-104 的 `sc-web-nginx` 容器托管，届时由 Nginx 承担同样的反向代理职责。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/webhooks': { target: proxyTarget, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      rollupOptions: {
        output: {
          // Element Plus 采用全量引入（不引入 unplugin-auto-import / unplugin-vue-components
          // 两个额外构建期依赖），代价是主包偏大。这里按库拆包，让 vendor 与业务代码分开缓存，
          // 避免每次改业务代码都让用户重新下载整个组件库。
          manualChunks: {
            'vendor-element-plus': ['element-plus'],
            'vendor-vue': ['vue', 'vue-router', 'pinia'],
          },
        },
      },
    },
  }
})
