import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  normalizeOperatorQq,
  readRuntimeOperatorQq,
  resolveOperatorQq,
} from '@/utils/operator-contact'

describe('部署方联系 QQ 解析', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('保留纯数字号码', () => {
    expect(normalizeOperatorQq('123456789')).toBe('123456789')
  })

  it('丢弃空白与杂字符（复制号码时常见的格式污染）', () => {
    expect(normalizeOperatorQq(' 123 456 789 ')).toBe('123456789')
    expect(normalizeOperatorQq('QQ:123456789')).toBe('123456789')
  })

  it('长度不在 5~12 位之间视为无效，返回空串', () => {
    expect(normalizeOperatorQq('1234')).toBe('')
    expect(normalizeOperatorQq('1234567890123')).toBe('')
    expect(normalizeOperatorQq('abc')).toBe('')
    expect(normalizeOperatorQq(undefined)).toBe('')
  })

  it('运行期值优先于构建期兜底值', () => {
    expect(resolveOperatorQq('123456', '987654321')).toBe('123456')
    expect(resolveOperatorQq('', '987654321')).toBe('987654321')
    expect(resolveOperatorQq(undefined, undefined)).toBe('')
  })

  it('读取 window.__SC_OPERATOR_QQ__', () => {
    vi.stubGlobal('window', { __SC_OPERATOR_QQ__: '31415926' })
    expect(readRuntimeOperatorQq()).toBe('31415926')
  })
})
