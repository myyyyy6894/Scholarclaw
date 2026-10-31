import { ref } from 'vue'

import { defineStore } from 'pinia'

/**
 * 工作台瞬时 UI 状态（P1）。
 *
 * 只放“打开/关闭”这类不需要持久化、也不属于业务数据的开关：
 * 移动端侧栏抽屉、通知中心抽屉。主题偏好不在这里，见 `composables/use-theme.ts`；
 * 任何密钥、令牌、用户内容都不得进入本 store。
 */
export const useUiStore = defineStore('ui', () => {
  const mobileSidebarOpen = ref(false)
  const notificationDrawerOpen = ref(false)
  /** 由通知中心写入，供侧栏角标复用，避免重复轮询 `API-NOTIF-001`。 */
  const unreadNotificationCount = ref(0)

  function openMobileSidebar(): void {
    mobileSidebarOpen.value = true
  }

  function closeMobileSidebar(): void {
    mobileSidebarOpen.value = false
  }

  function openNotificationDrawer(): void {
    notificationDrawerOpen.value = true
  }

  function closeNotificationDrawer(): void {
    notificationDrawerOpen.value = false
  }

  function setUnreadNotificationCount(count: number): void {
    unreadNotificationCount.value = Number.isFinite(count) && count > 0 ? Math.trunc(count) : 0
  }

  return {
    mobileSidebarOpen,
    notificationDrawerOpen,
    unreadNotificationCount,
    openMobileSidebar,
    closeMobileSidebar,
    openNotificationDrawer,
    closeNotificationDrawer,
    setUnreadNotificationCount,
  }
})
