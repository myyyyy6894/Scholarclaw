import type { DocumentStatus, KnowledgeDoc } from '@/api/knowledge'

/** 文档表格的纯展示/状态判断；便于单测，不依赖组件。 */

export type TagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

export function documentStatusLabel(status: DocumentStatus): string {
  switch (status) {
    case 'UPLOADED':
      return '待解析'
    case 'PARSING':
      return '解析中'
    case 'READY':
      return '就绪'
    case 'FAILED':
      return '失败'
    case 'DELETING':
      return '删除中'
    case 'DELETED':
      return '已删除'
    default:
      return status
  }
}

export function documentStatusTagType(status: DocumentStatus): TagType {
  switch (status) {
    case 'READY':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PARSING':
    case 'UPLOADED':
      return 'warning'
    default:
      return 'info'
  }
}

export function canDelete(doc: KnowledgeDoc): boolean {
  return doc.status !== 'DELETING' && doc.status !== 'DELETED'
}

/** 仅 FAILED 可手动重试（`API-DOC-003` 状态门控）。 */
export function canRetry(doc: KnowledgeDoc): boolean {
  return doc.status === 'FAILED'
}

/** 仅 READY 可单文档重建（`API-DOC-004` 状态门控）。 */
export function canReindex(doc: KnowledgeDoc): boolean {
  return doc.status === 'READY'
}

/** 存在待解析/解析中文档时需要轮询刷新，让异步结果自动出现在页面上。 */
export function hasPendingDocs(docs: KnowledgeDoc[]): boolean {
  return docs.some((doc) => doc.status === 'UPLOADED' || doc.status === 'PARSING')
}

/** 只有 READY 才展示索引快照；非 READY 一律按 0 渲染，避免历史脏数据误导。 */
export function snapshotPageCount(doc: KnowledgeDoc): number {
  return doc.status === 'READY' ? doc.page_count : 0
}

export function snapshotChunkCount(doc: KnowledgeDoc): number {
  return doc.status === 'READY' ? doc.chunk_count : 0
}

export function snapshotReadyAt(doc: KnowledgeDoc): string | null {
  return doc.status === 'READY' ? doc.ready_at : null
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 请求序号门：快速切换课程时只接受最后一次发起的响应。 */
export class LatestRequest {
  private seq = 0

  start(): number {
    this.seq += 1
    return this.seq
  }

  isCurrent(token: number): boolean {
    return token === this.seq
  }
}

export const MAX_UPLOAD_BYTES = 52_428_800
export const UPLOAD_ACCEPT = '.pdf,.md,.txt'

/** 客户端预检只做即时反馈；服务端仍执行完整四重校验。 */
export function uploadFileError(file: File): string | null {
  const lower = file.name.toLowerCase()
  if (!['.pdf', '.md', '.txt'].some((ext) => lower.endsWith(ext))) {
    return '仅支持 .pdf、.md、.txt 格式的课件。'
  }
  if (file.size <= 0) {
    return '不能上传空文件。'
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return '文件超过 50MB 上限，请压缩或拆分后重试。'
  }
  return null
}
