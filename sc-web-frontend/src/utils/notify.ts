import { ElMessage } from 'element-plus'

import { ApiError } from '@/api/error'

/**
 * 统一的用户提示出口。
 *
 * 约定：
 *
 * * 服务端错误一律展示后端返回的 `message`（后端已脱敏），并附上 `trace_id` 便于用户报障时复制；
 * * **纯客户端错误**（`CLIENT_*`）没有追踪号，只展示可读原因，不得伪造一个追踪号；
 * * 未识别的异常只给通用文案，绝不把堆栈或原始对象抛给用户。
 */
export function notifyError(error: unknown, fallback = '操作失败，请稍后重试。'): void {
  if (error instanceof ApiError) {
    const suffix = error.traceId && !error.isClientSide ? `（追踪号：${error.traceId}）` : ''
    ElMessage.error(`${error.message}${suffix}`)
    return
  }
  ElMessage.error(fallback)
}

export function notifyWarning(message: string): void {
  ElMessage.warning(message)
}
