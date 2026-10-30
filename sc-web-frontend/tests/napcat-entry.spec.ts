import { describe, expect, it } from 'vitest'

import {
  DEFAULT_NAPCAT_WEBUI_URL,
  resolveNapcatWebuiUrl,
} from '@/views/settings/napcat-entry'

describe('NapCat WebUI entry', () => {
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
})
