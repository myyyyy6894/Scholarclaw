/**
 * 使用指南的「已看过」标记。
 *
 * 放在 `utils` 而不是 `views`：`tests/no-secret-persistence.spec.ts` 禁止
 * `src/views/**` 直接引用任何浏览器存储（那条红线是为密钥设的，这里也不破例）。
 * 标记本身不是敏感信息，只是"别再自动弹指南了"。
 */
import { browserStorage } from '@/utils/browser-storage'

export const GUIDE_SEEN_KEY = 'sc.guide.seen'

export function hasSeenGuide(): boolean {
  return browserStorage.read<boolean>(GUIDE_SEEN_KEY) === true
}

export function markGuideSeen(): void {
  browserStorage.write(GUIDE_SEEN_KEY, true)
}
