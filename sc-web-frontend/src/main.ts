import { createApp } from 'vue'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'

import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/tokens.css'
import '@/styles/base.css'

import App from '@/App.vue'
import { configureApiClient } from '@/api/client'
import { initTheme } from '@/composables/use-theme'
import { router } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifyWarning } from '@/utils/notify'

/**
 * 应用装配入口。
 *
 * 装配顺序值得注意：先装 Pinia，才能取得鉴权 store 实例并注入 API client；
 * client 与 store 之间通过回调解耦，任何一方都不 import 另一方，避免循环依赖。
 * 主题在 mount 前初始化，避免首屏先渲染浅色再切换。
 */

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

const auth = useAuthStore()

configureApiClient({
  tokenProvider: () => auth.getToken(),
  unauthorizedHandler: () => {
    if (!auth.isAuthenticated) {
      return
    }
    auth.clearSession()
    notifyWarning('登录状态已失效，请重新登录。')
    const current = router.currentRoute.value
    if (current.name !== 'login') {
      void router.push({ name: 'login', query: { redirect: current.fullPath } })
    }
  },
})

app.config.errorHandler = (error) => {
  notifyError(error)
}

app.mount('#app')
