import { describe, expect, it } from 'vitest'

import type { KnowledgeDoc } from '@/api/knowledge'
import {
  canDelete,
  canReindex,
  canRetry,
  documentStatusLabel,
  hasPendingDocs,
  snapshotChunkCount,
  snapshotPageCount,
  snapshotReadyAt,
  LatestRequest,
  documentStatusTagType,
  formatFileSize,
  uploadFileError,
} from '@/views/knowledge/knowledge-form'

function doc(status: KnowledgeDoc['status']): KnowledgeDoc {
  return {
    doc_id: '50001',
    course_id: '2001',
    doc_name: 'a.pdf',
    file_ext: '.pdf',
    file_size_bytes: 1024,
    status,
    parse_version: 1,
    course_index_version: 1,
    indexed_embedding_model: null,
    indexed_embedding_dim: null,
    page_count: 0,
    chunk_count: 0,
    retry_count: 0,
    last_ingest_job_id: null,
    error_code: null,
    error_msg: null,
    enqueued_at: null,
    ready_at: null,
    created_at: '2026-10-21T10:00:00.000+08:00',
    updated_at: '2026-10-21T10:00:00.000+08:00',
  }
}

describe('knowledge document presentation', () => {
  it('状态映射为可读文案与标签类型', () => {
    expect(documentStatusLabel('UPLOADED')).toBe('待解析')
    expect(documentStatusLabel('PARSING')).toBe('解析中')
    expect(documentStatusLabel('READY')).toBe('就绪')
    expect(documentStatusLabel('FAILED')).toBe('失败')
    expect(documentStatusLabel('DELETING')).toBe('删除中')
    expect(documentStatusLabel('DELETED')).toBe('已删除')
    expect(documentStatusTagType('READY')).toBe('success')
    expect(documentStatusTagType('FAILED')).toBe('danger')
  })

  it('非删除态可删除', () => {
    expect(canDelete(doc('READY'))).toBe(true)
    expect(canDelete(doc('DELETING'))).toBe(false)
    expect(canDelete(doc('DELETED'))).toBe(false)
  })

  it('仅 FAILED 可重试、仅 READY 可重建', () => {
    expect(canRetry(doc('FAILED'))).toBe(true)
    expect(canRetry(doc('READY'))).toBe(false)
    expect(canRetry(doc('PARSING'))).toBe(false)
    expect(canReindex(doc('READY'))).toBe(true)
    expect(canReindex(doc('FAILED'))).toBe(false)
    expect(canReindex(doc('PARSING'))).toBe(false)
  })

  it('存在解析中文档时启用轮询', () => {
    expect(hasPendingDocs([doc('READY'), doc('FAILED')])).toBe(false)
    expect(hasPendingDocs([doc('READY'), doc('PARSING')])).toBe(true)
    expect(hasPendingDocs([doc('UPLOADED')])).toBe(true)
  })

  it('快速切换课程时旧请求序号失效', () => {
    const gate = new LatestRequest()
    const first = gate.start()
    const second = gate.start()
    expect(gate.isCurrent(first)).toBe(false)
    expect(gate.isCurrent(second)).toBe(true)
  })

  it('非 READY 文档隐藏旧索引快照（页/块为 0，ready_at 为 null）', () => {
    const parsing = doc('PARSING')
    parsing.page_count = 38
    parsing.chunk_count = 112
    parsing.indexed_embedding_model = 'old-model'
    parsing.indexed_embedding_dim = 1024
    parsing.ready_at = '2026-10-21T10:00:00.000+08:00'

    expect(snapshotPageCount(parsing)).toBe(0)
    expect(snapshotChunkCount(parsing)).toBe(0)
    expect(snapshotReadyAt(parsing)).toBeNull()
  })

  it('文件大小格式化', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(2 * 1024 * 1024)).toBe('2.0 MB')
  })

  it('客户端上传预检拒绝空文件、超限与非白名单扩展名', () => {
    expect(uploadFileError(new File(['x'], 'a.pdf'))).toBeNull()
    expect(uploadFileError(new File([''], 'a.pdf'))).toContain('空文件')
    expect(uploadFileError(new File(['MZ'], 'a.exe'))).toContain('.pdf')
    const oversized = new File(['x'], 'big.pdf')
    Object.defineProperty(oversized, 'size', { value: 52_428_801 })
    expect(uploadFileError(oversized)).toContain('50MB')
  })
})
