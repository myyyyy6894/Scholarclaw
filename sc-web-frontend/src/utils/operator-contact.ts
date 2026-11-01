/**
 * 部署方联系 QQ 解析（可选功能）。
 *
 * 自建部署者可以把自己的 QQ 作为反馈入口显示出来；数据来自 Nginx 启动脚本写入的
 * `window.__SC_OPERATOR_QQ__`（环境变量 `SC_OPERATOR_QQ`），改号只需重启容器。
 *
 * 三条约束：
 * 1. 只接受 5~12 位数字，任何其它字符一律丢弃——避免把配置错误渲染成奇怪的号码；
 * 2. 未配置时返回空串，调用方必须整块隐藏，不显示「未配置」占位；
 * 3. 这是**联系方式**，不是 IM 绑定入口：没有运行 NapCat 时加好友收不到 DDL 提醒。
 */
declare global {
  interface Window {
    __SC_OPERATOR_QQ__?: string
    __SC_PROJECT_URL__?: string
  }
}

/** 常见 QQ 号长度范围；超出即视为配置错误。 */
export const OPERATOR_QQ_MIN_LENGTH = 5
export const OPERATOR_QQ_MAX_LENGTH = 12

/** 读取运行期注入值；非浏览器环境返回 `undefined`。 */
export function readRuntimeOperatorQq(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  return window.__SC_OPERATOR_QQ__
}

/** 归一化：只保留数字，长度不在范围内返回空串。 */
export function normalizeOperatorQq(value?: string): string {
  const digits = (value ?? '').replace(/\D/g, '')
  if (digits.length < OPERATOR_QQ_MIN_LENGTH || digits.length > OPERATOR_QQ_MAX_LENGTH) {
    return ''
  }
  return digits
}

/** 组合运行期值与构建期兜底值（运行期优先）。 */
export function resolveOperatorQq(runtime?: string, fallback?: string): string {
  return normalizeOperatorQq(runtime) || normalizeOperatorQq(fallback)
}

/** 复制 QQ 号；浏览器不支持或权限被拒时返回 false，由调用方提示手动复制。 */
export async function copyOperatorQq(qq: string): Promise<boolean> {
  if (!qq || typeof navigator === 'undefined' || !navigator.clipboard) {
    return false
  }
  try {
    await navigator.clipboard.writeText(qq)
    return true
  } catch {
    return false
  }
}

/**
 * 项目主页地址（`SC_PROJECT_URL`，非密钥，可选）。
 *
 * 只接受 http/https 绝对地址；其它值一律当作未配置，避免把配置错误渲染成可点击链接。
 */
export function readRuntimeProjectUrl(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  return window.__SC_PROJECT_URL__
}

export function normalizeProjectUrl(value?: string): string {
  const candidate = (value ?? '').trim()
  if (!/^https?:\/\/[^\s]+$/i.test(candidate)) {
    return ''
  }
  return candidate
}
