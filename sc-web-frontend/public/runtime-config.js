// 运行期配置：由 sc-web-nginx 容器启动脚本按 SC_NAPCAT_WEBUI_URL 覆盖。
// 本地开发或未注入时保持为空，前端回落到构建期 VITE_NAPCAT_WEBUI_URL 或默认值。
// 这里只允许放**非密钥**的跳转地址，禁止写入任何令牌/密钥。
window.__SC_NAPCAT_WEBUI_URL__ = window.__SC_NAPCAT_WEBUI_URL__ || ''
