/**
 * NapCat WebUI 入口地址解析（QQ 扫码登录由独立协议端完成）。
 *
 * 业务前端不代理 NapCat 的登录页，只提供跳转入口；默认值与
 * `docker-compose.yml` 中 NapCat WebUI 的宿主映射一致（127.0.0.1:6099）。
 * 远程部署时必须通过 `VITE_NAPCAT_WEBUI_URL` 改成浏览器可达的地址。
 */
export const DEFAULT_NAPCAT_WEBUI_URL = 'http://127.0.0.1:6099'

export function resolveNapcatWebuiUrl(configured?: string): string {
  const value = (configured ?? '').trim()
  return value || DEFAULT_NAPCAT_WEBUI_URL
}
