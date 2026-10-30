/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

/**
 * 前端可见的构建期变量。Vite 只把 `VITE_` 前缀的变量注入客户端包，
 * 因此**任何密钥都不得以 `VITE_` 前缀命名**（`05` §4.6：真实密钥只按架构注入后端容器）。
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_DEV_PROXY_TARGET?: string
  readonly VITE_APP_TITLE?: string
  /** NapCat WebUI 跳转地址（非密钥）；远程部署时改成浏览器可达地址。 */
  readonly VITE_NAPCAT_WEBUI_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
