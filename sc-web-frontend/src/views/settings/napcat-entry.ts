/**
 * NapCat WebUI 入口地址解析（QQ 扫码登录由独立协议端完成）。
 *
 * 业务前端不代理 NapCat 的登录页，只提供跳转入口。地址优先级：
 * 1. 运行期注入 `window.__SC_NAPCAT_WEBUI_URL__`（`SC_NAPCAT_WEBUI_URL`，重启容器即可生效）；
 * 2. 构建期 `VITE_NAPCAT_WEBUI_URL`；
 * 3. 默认 `http://127.0.0.1:6099`（与 docker-compose 中 NapCat WebUI 的默认宿主映射一致）。
 *
 * 只接受**非密钥**地址；WebUI 访问令牌永远由运维在浏览器端自行持有，不进入本配置。
 */
export const DEFAULT_NAPCAT_WEBUI_URL = 'http://127.0.0.1:6099'

declare global {
  interface Window {
    __SC_NAPCAT_WEBUI_URL__?: string
  }
}

export function readRuntimeNapcatWebuiUrl(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  return window.__SC_NAPCAT_WEBUI_URL__
}

export function resolveNapcatWebuiUrl(configured?: string, runtime?: string): string {
  for (const candidate of [runtime, configured]) {
    const value = (candidate ?? '').trim()
    if (value) {
      return value
    }
  }
  return DEFAULT_NAPCAT_WEBUI_URL
}
