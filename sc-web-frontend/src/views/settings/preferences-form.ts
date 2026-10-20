import type { ImPlatform, PreferencesUpdateRequest, UserConfig } from '@/api/user-config'

/** 个人偏好表单的纯状态与纯函数；不含任何存储调用。 */

export interface PreferencesFormState {
  timezone: string
  dndStartTime: string
  dndEndTime: string
  imPlatform: ImPlatform | ''
  imUid: string
}

export class PreferencesValidationError extends Error {}

export function createPreferencesForm(config?: UserConfig | null): PreferencesFormState {
  return {
    timezone: config?.timezone ?? 'Asia/Shanghai',
    dndStartTime: config?.dnd_start_time ?? '23:30:00',
    dndEndTime: config?.dnd_end_time ?? '07:30:00',
    imPlatform: config?.bound_im_platform ?? '',
    imUid: config?.bound_im_uid ?? '',
  }
}

/**
 * 校验并构造请求。
 *
 * 时间统一补齐为 `HH:mm:ss`（`04` §2.5）；IM 平台与账号必须成对
 * （数据库 `chk_user_im_pair`，前端提前给出可读提示）。
 */
export function toPreferencesRequest(form: PreferencesFormState): PreferencesUpdateRequest {
  const timezone = form.timezone.trim()
  if (timezone === '') {
    throw new PreferencesValidationError('请填写 IANA 时区，例如 Asia/Shanghai。')
  }
  // `el-select` 清空时可能给出空串或 undefined；两者都按"解绑"处理
  const platform = form.imPlatform ? form.imPlatform : null
  const uid = form.imUid.trim() === '' ? null : form.imUid.trim()
  if ((platform === null) !== (uid === null)) {
    throw new PreferencesValidationError('IM 平台与 IM 账号必须同时填写或同时留空。')
  }
  return {
    timezone,
    dnd_start_time: normalizeTime(form.dndStartTime, '免打扰开始时间'),
    dnd_end_time: normalizeTime(form.dndEndTime, '免打扰结束时间'),
    bound_im_platform: platform,
    bound_im_uid: uid,
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
