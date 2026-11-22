import type { PreferencesUpdateRequest, UserConfig } from '@/api/user-config'

/** 个人偏好表单的纯状态与纯函数；不含任何存储调用。 */

export interface PreferencesFormState {
  timezone: string
  dndStartTime: string
  dndEndTime: string
}

export class PreferencesValidationError extends Error {}

export function createPreferencesForm(config?: UserConfig | null): PreferencesFormState {
  return {
    timezone: config?.timezone ?? 'Asia/Shanghai',
    dndStartTime: config?.dnd_start_time ?? '23:30:00',
    dndEndTime: config?.dnd_end_time ?? '07:30:00',
  }
}

/**
 * 校验并构造请求。
 *
 * 时间统一补齐为 `HH:mm:ss`（`04` §2.5）。
 */
export function toPreferencesRequest(form: PreferencesFormState): PreferencesUpdateRequest {
  const timezone = form.timezone.trim()
  if (timezone === '') {
    throw new PreferencesValidationError('请填写 IANA 时区，例如 Asia/Shanghai。')
  }
  return {
    timezone,
    dnd_start_time: normalizeTime(form.dndStartTime, '免打扰开始时间'),
    dnd_end_time: normalizeTime(form.dndEndTime, '免打扰结束时间'),
  }
}

function normalizeTime(value: string, label: string): string {
  const trimmed = value.trim()
  if (/^\d{2}:\d{2}$/.test(trimmed)) {
    return `${trimmed}:00`
  }
  if (/^\d{2}:\d{2}:\d{2}$/.test(trimmed)) {
    return trimmed
  }
  throw new PreferencesValidationError(`${label}格式必须为 HH:mm 或 HH:mm:ss。`)
}
