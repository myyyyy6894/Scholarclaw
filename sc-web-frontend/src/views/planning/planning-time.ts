/** 当天分钟表示避免把本地作息误当成浏览器时区的绝对时间。 */
export function parseLocalMinute(value: string, allowMidnightEnd = false): number {
  const match = /^(\d{2}):(\d{2})$/.exec(value.trim())
  if (!match) throw new Error('请按 08:30 的格式填写时间。')
  const hour = Number(match[1]), minute = Number(match[2])
  if (hour === 24 && minute === 0 && allowMidnightEnd) return 1440
  if (hour > 23 || minute > 59) throw new Error('时间必须在 00:00 至 23:59 之间；结束时间可填 24:00。')
  return hour * 60 + minute
}
export function formatLocalMinute(value: number): string {
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
}
export function requireOffsetTimestamp(value: string, label = '最早开始时间'): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2})$/.test(trimmed)
    || Number.isNaN(Date.parse(trimmed))) throw new Error(`${label}请填写带时区偏移的时间，例如 2026-11-09T09:00+08:00。`)
  return trimmed
}
