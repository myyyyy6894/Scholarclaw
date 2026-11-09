import { describe, expect, it } from 'vitest'
import { formatClock, mondayOf, parseWeeks, shiftDate, todayInZone, weekDates } from '@/views/schedule/schedule-date'

describe('课表日期与周次', () => {
  it('按学期时区判断当天并跨年计算周日', () => {
    const now = new Date('2026-12-27T16:30:00Z')
    expect(todayInZone('Asia/Shanghai', now)).toBe('2026-12-28')
    expect(todayInZone('America/Los_Angeles', now)).toBe('2026-12-27')
    expect(mondayOf('2027-01-03')).toBe('2026-12-28')
    expect(weekDates('2027-01-03')[6]).toBe('2027-01-03')
    expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01')
    expect(formatClock('2027-01-03T08:00:00+08:00', 'Asia/Shanghai')).toBe('08:00')
  })

  it('把非连续周和单双周解析成明确集合', () => {
    expect(parseWeeks('1-8单周、10，12-16双周', 20)).toEqual([1, 3, 5, 7, 10, 12, 14, 16])
    expect(parseWeeks('1-6周单周', 16)).toEqual([1, 3, 5])
    expect(() => parseWeeks('1-30', 16)).toThrow()
    expect(() => parseWeeks('1-4左右', 16)).toThrow()
    expect(() => parseWeeks('2单周', 16)).toThrow()
  })
})
