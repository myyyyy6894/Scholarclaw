import { afterEach, describe, expect, it } from 'vitest'

import {
  DEFAULT_NAPCAT_WEBUI_URL,
  readRuntimeNapcatWebuiUrl,
  resolveNapcatWebuiUrl,
} from '@/views/settings/napcat-entry'

describe('NapCat WebUI entry', () => {
  afterEach(() => {
    delete (globalThis as { window?: unknown }).window
  })

  it('未配置时使用本机默认地址', () => {
    expect(resolveNapcatWebuiUrl(undefined)).toBe(DEFAULT_NAPCAT_WEBUI_URL)
    expect(resolveNapcatWebuiUrl('   ')).toBe(DEFAULT_NAPCAT_WEBUI_URL)
    expect(DEFAULT_NAPCAT_WEBUI_URL).toBe('http://127.0.0.1:6099')
  })

  it('远程部署时使用构建期配置的可达地址', () => {
    expect(resolveNapcatWebuiUrl('http://192.168.1.10:6099'))
      .toBe('http://192.168.1.10:6099')
    expect(resolveNapcatWebuiUrl('  http://napcat.example.com:6099  '))
      .toBe('http://napcat.example.com:6099')
  })

  it('运行期配置优先于构建期配置', () => {
    expect(resolveNapcatWebuiUrl('http://build:6099', 'http://runtime:6099'))
      .toBe('http://runtime:6099')
    expect(resolveNapcatWebuiUrl('http://build:6099', '   '))
      .toBe('http://build:6099')
    expect(resolveNapcatWebuiUrl(undefined, '  http://runtime:6099  '))
      .toBe('http://runtime:6099')
    expect(resolveNapcatWebuiUrl(undefined, '')).toBe(DEFAULT_NAPCAT_WEBUI_URL)
  })

  it('运行期配置从 window 读取，无 window 时返回 undefined', () => {
    expect(readRuntimeNapcatWebuiUrl()).toBeUndefined()
    ;(globalThis as { window?: unknown }).window = {
      __SC_NAPCAT_WEBUI_URL__: 'http://runtime.example.com:6099',
    }
    expect(readRuntimeNapcatWebuiUrl()).toBe('http://runtime.example.com:6099')
  })
})
