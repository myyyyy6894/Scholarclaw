import { describe, expect, it } from 'vitest'
import { formatLocalMinute, parseLocalMinute, requireOffsetTimestamp } from '@/views/planning/planning-time'

describe('规划资料时间', () => {
  it('按用户指定的墙钟时间读写，包括一天结束', () => {
    expect(parseLocalMinute('08:30')).toBe(510)
    expect(parseLocalMinute('24:00', true)).toBe(1440)
    expect(formatLocalMinute(1440)).toBe('24:00')
    expect(() => parseLocalMinute('24:00')).toThrow()
    expect(() => parseLocalMinute('08:60')).toThrow()
  })
  it('绝对开始时间必须带偏移，空值不推测', () => {
    expect(requireOffsetTimestamp('')).toBeNull()
    expect(requireOffsetTimestamp('2026-11-09T09:00+08:00')).toBe('2026-11-09T09:00+08:00')
    expect(() => requireOffsetTimestamp('2026-11-09T09:00')).toThrow()
  })
})
