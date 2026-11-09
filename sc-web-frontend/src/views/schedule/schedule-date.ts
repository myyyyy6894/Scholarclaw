/** 日期运算只使用 UTC 日历字段；取“今天”时显式指定课表时区。 */
export function todayInZone(timezone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const value = (type: Intl.DateTimeFormatPartTypes): string => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

export function shiftDate(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number)
  const result = new Date(Date.UTC(year, month - 1, day + days))
  return `${result.getUTCFullYear().toString().padStart(4, '0')}-${(result.getUTCMonth() + 1).toString().padStart(2, '0')}-${result.getUTCDate().toString().padStart(2, '0')}`
}

export function mondayOf(iso: string): string {
  const weekday = new Date(`${iso}T00:00:00Z`).getUTCDay()
  return shiftDate(iso, -((weekday + 6) % 7))
}

export function weekDates(iso: string): string[] {
  const monday = mondayOf(iso)
  return Array.from({ length: 7 }, (_, index) => shiftDate(monday, index))
}

export function formatClock(isoOffset: string, timezone: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(isoOffset))
}

export function formatClockSeconds(isoOffset: string, timezone: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone, hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(new Date(isoOffset))
}

/** 支持“1-16单周、18、20-22”等明确周次，绝不猜测缺失数字。 */
export function parseWeeks(text: string, weekCount: number): number[] {
  const value = text.trim().replaceAll('，', ',').replaceAll('、', ',').replaceAll(' ', '')
  if (!value) throw new Error('请填写上课周次，例如 1-16 或 1-16单周。')
  const result = new Set<number>()
  for (const part of value.split(',')) {
    const match = /^(\d{1,2})(?:-(\d{1,2}))?(?:周)?(单周|双周)?$/.exec(part)
    if (!match) throw new Error('周次格式不正确，例如 1-16、18 或 1-16单周。')
    const start = Number(match[1])
    const end = match[2] ? Number(match[2]) : start
    if (start < 1 || end < start || end > weekCount) throw new Error(`周次须在 1 至 ${weekCount} 周之间。`)
    for (let week = start; week <= end; week++) {
      if (match[3] === '单周' && week % 2 === 0) continue
      if (match[3] === '双周' && week % 2 !== 0) continue
      result.add(week)
    }
  }
  if (result.size === 0) throw new Error('所选周次为空，请重新填写。')
  return [...result].sort((a, b) => a - b)
}
