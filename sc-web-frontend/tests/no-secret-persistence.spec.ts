import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * 密钥不得进入浏览器持久化存储（`06` TASK-205 验收条件、`05` §4.4）。
 *
 * 用源码级扫描而不是只靠人工 review：只要有人在设置页或用户配置 API 里引入
 * `localStorage` / `sessionStorage` / `indexedDB`，这条断言就会失败。
 * 允许的存储只有 `src/stores/auth.ts` 里的登录会话（令牌 + 展示信息）。
 */

const SRC = resolve(process.cwd(), 'src')

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) {
      return walk(path)
    }
    return /\.(ts|vue)$/.test(entry) ? [path] : []
  })
}

const FORBIDDEN = /localStorage|sessionStorage|browserStorage|indexedDB/

describe('secret persistence guard', () => {
  it('设置页与用户配置 API 不引用任何浏览器存储', () => {
    const files = [...walk(join(SRC, 'views')), join(SRC, 'api', 'user-config.ts'), join(SRC, 'api', 'auth.ts')]

    const offenders = files
      .filter((file) => FORBIDDEN.test(readFileSync(file, 'utf8')))
      .map((file) => relative(SRC, file))

    expect(offenders).toEqual([])
  })

  it('登录会话只持久化 token 与 user_id/username', () => {
    const source = readFileSync(join(SRC, 'stores', 'auth.ts'), 'utf8')

    expect(source).toContain('token')
    expect(source).toContain('username')
    expect(source).not.toMatch(/api[_-]?key/i)
    expect(source).not.toMatch(/password/i)
  })
})
