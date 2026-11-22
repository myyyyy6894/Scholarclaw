import type { SchedulePeriod } from '@/api/schedule'

export interface PeriodBlock {
  start: string
  count: number | undefined
}

function minutes(value: string): number {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) throw new Error('请填写有效的首节开始时间。')
  const [hour, minute] = value.split(':').map(Number)
  return hour * 60 + minute
}

function clock(value: number): string {
  return [Math.floor(value / 60), value % 60, 0].map((part) => String(part).padStart(2, '0')).join(':')
}

export function generatePeriods(blocks: PeriodBlock[], duration: number | undefined, gap: number | undefined,
  existing: SchedulePeriod[], replaceExisting: boolean): SchedulePeriod[] {
  if (duration === undefined || !Number.isInteger(duration) || duration < 1 || duration > 180
    || gap === undefined || !Number.isInteger(gap) || gap < 0 || gap > 120) {
    throw new Error('请填写有效的课时和课间分钟数。')
  }
  const generated: SchedulePeriod[] = []
  let previousEnd = 0
  for (const { start, count } of blocks) {
    if (count === undefined || !Number.isInteger(count) || count < 0 || generated.length + count > 30) {
      throw new Error('请填写各时段的节数，总节数最多为 30。')
    }
    if (count === 0) continue
    let nextStart = minutes(start)
    if (nextStart < previousEnd) throw new Error('各时段必须依次开始且不能重叠，请调整开始时间或节数。')
    for (let index = 0; index < count; index += 1) {
      previousEnd = nextStart + duration
      if (previousEnd >= 24 * 60) throw new Error('生成的节次跨过了午夜，请调整开始时间或节数。')
      generated.push({ period_no: generated.length + 1, start_time: clock(nextStart), end_time: clock(previousEnd) })
      nextStart = previousEnd + gap
    }
  }
  if (generated.length === 0) throw new Error('至少填写一个时段的节数。')
  const byNumber = new Map(generated.map((period) => [period.period_no, period]))
  for (const period of existing) {
    if (!replaceExisting || !byNumber.has(period.period_no)) byNumber.set(period.period_no, period)
  }
  return [...byNumber.values()].sort((a, b) => a.period_no - b.period_no)
}
