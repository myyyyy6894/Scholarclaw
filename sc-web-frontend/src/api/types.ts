/**
 * 统一响应信封（`04` §2.3.1）。
 *
 * 与 Java `Result<T>`、Python `InternalResponse[T]` 字段完全一致，前端**禁止**再造第二套包装。
 * 字段名为 `snake_case`，与后端 JSON 原样对应，不做驼峰转换——避免在转换层引入静默丢字段的问题。
 */
export interface ApiResult<T> {
  code: string
  message: string
  data: T | null
  trace_id: string
  timestamp: string
}

/** 成功状态码，固定为 `"OK"`（`04` §2.3.1）。 */
export const SUCCESS_CODE = 'OK'

/**
 * `BIGINT` 业务 ID 在**全链路**都是十进制字符串（`04` §2.6）。
 *
 * 这个别名存在的唯一目的是让类型声明自带约束说明：前端对 ID 必须全程按 `string` 保存、
 * 比较、拼接 URL 与回传，**严禁** `Number(id)` / `parseInt(id)` / `+id` 转成 JS Number
 * ——`BIGINT` 超出 `Number.MAX_SAFE_INTEGER` 后会静默丢精度。
 */
export type BigIntId = string
