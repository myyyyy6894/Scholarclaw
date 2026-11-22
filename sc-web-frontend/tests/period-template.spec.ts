import { describe, expect, it } from 'vitest'

import { generatePeriods } from '@/views/schedule/period-template'

describe('period template', () => {
  it('starts independent morning, afternoon and evening blocks for an eleven-period import', () => {
    const periods = generatePeriods([
      { start: '07:50', count: 4 }, { start: '14:30', count: 4 }, { start: '18:40', count: 3 },
    ], 50, 10, [], false)
    expect(periods).toHaveLength(11)
    expect([periods[0], periods[4], periods[10]]).toEqual([
      { period_no: 1, start_time: '07:50:00', end_time: '08:40:00' },
      { period_no: 5, start_time: '14:30:00', end_time: '15:20:00' },
      { period_no: 11, start_time: '20:40:00', end_time: '21:30:00' },
    ])
  })

  it('preserves existing clocks by default and can replace generated ranges', () => {
    const block = { start: '08:00', count: 2 }
    const manual = [
      { period_no: 1, start_time: '07:55:00', end_time: '08:40:00' },
      { period_no: 3, start_time: '11:30:00', end_time: '12:15:00' },
    ]
    expect(generatePeriods([block], 45, 10, manual, false)[0]).toEqual(manual[0])
    const replaced = generatePeriods([block], 45, 10, manual, true)
    expect(replaced[0].start_time).toBe('08:00:00')
    expect(replaced[2]).toEqual(manual[1])
  })

  it('rejects incomplete, overlapping or overnight templates', () => {
    expect(() => generatePeriods([{ start: '08:00', count: 2 }], undefined, 10, [], false)).toThrow()
    expect(() => generatePeriods([{ start: '08:00', count: 2 }, { start: '08:45', count: 1 }], 45, 10, [], false)).toThrow('重叠')
    expect(() => generatePeriods([{ start: '23:30', count: 1 }], 45, 10, [], false)).toThrow('午夜')
  })
})
