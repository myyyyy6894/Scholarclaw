/**
 * 首次登录/注册后只跳一次「使用指南」。
 *
 * 规则：
 * 1. 已经看过就不再打扰（标记存在浏览器本地，换设备会再看一次，这是可接受的）；
 * 2. 用户带着 `redirect` 进来（比如从对话页被踢回登录）时不打断他的原目标；
 * 3. 判断与副作用分开：纯函数负责决策，写标记由调用方完成，便于单测。
 */
/**
 * 计算登录后应该跳到哪里。
 *
 * @param seen         本地是否已标记"看过指南"
 * @param hasRedirect  本次登录是否带有明确的 redirect 目标
 * @param currentPath  当前路径（已在指南页时不再跳转）
 * @returns 需要跳转的路径；`null` 表示按原逻辑跳转
 */
export function firstVisitGuideTarget(
  seen: boolean,
  hasRedirect: boolean,
  currentPath: string,
): string | null {
  if (seen) {
    return null
  }
  if (hasRedirect) {
    return null
  }
  if (currentPath.startsWith('/guide')) {
    return null
  }
  return '/guide'
}
