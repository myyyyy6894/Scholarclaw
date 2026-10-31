import { computed, onUnmounted, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'

import { listDdlTasks } from '@/api/ddl'
import { useAuthStore } from '@/stores/auth'

/**
 * 侧栏角标计数（P1）。
 *
 * 通知未读数由 `NotificationCenter` 写入 `ui` store，这里不重复请求；
 * DDL 待处理数需要一次看板请求，因为后端没有单独的计数接口。
 *
 * 计数失败不影响页面：角标静默归零，不弹错误提示（壳层不应因辅助信息失败而打扰用户）。
 */
const POLL_INTERVAL_MS = 60_000

export function useShellCounters(): {
  ddlPendingCount: ComputedRef<number>
  refreshDdlCount: () => Promise<void>
} {
  const auth = useAuthStore()
  const ddlPendingCount = ref(0)
  let timer: ReturnType<typeof setInterval> | null = null

  function stopPolling(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  async function refreshDdlCount(): Promise<void> {
    if (!auth.isAuthenticated) {
      ddlPendingCount.value = 0
      return
    }
    try {
      const board = await listDdlTasks({ statusGroup: 'ALL' })
      ddlPendingCount.value =
        board.pending_inbox_tasks.length + board.active_tasks.length
    } catch {
      // 壳层角标是辅助信息；401 由 API client 统一处理，其余错误静默。
      ddlPendingCount.value = 0
    }
  }

  watch(
    () => auth.isAuthenticated,
    (authenticated) => {
      stopPolling()
      if (!authenticated) {
        ddlPendingCount.value = 0
        return
      }
      void refreshDdlCount()
      timer = setInterval(() => {
        void refreshDdlCount()
      }, POLL_INTERVAL_MS)
    },
    { immediate: true },
  )

  onUnmounted(stopPolling)

  return { ddlPendingCount: computed(() => ddlPendingCount.value), refreshDdlCount }
}
