/**
 * 浏览器存储的薄封装。
 *
 * 存在的理由有两个，都很具体：
 *
 * 1. **可测试**：单元测试运行在 node 环境，没有 `localStorage`。这里在不可用时退化为进程内
 *    `Map`，使鉴权 store 的测试无需引入 jsdom 等重型依赖。
 * 2. **不崩**：某些隐私模式或浏览器策略下访问 `localStorage` 会直接抛异常，
 *    未包裹的调用会让整个应用白屏。
 *
 * **只用于非敏感状态**：当前仅存登录令牌与用户展示信息。模型 API Key 绝不允许落到浏览器存储
 * （`05` §4.4：密钥只在提交时使用，页面只展示服务端返回的掩码）。
 */

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

function createMemoryStorage(): StorageLike {
  const store = new Map<string, string>()
  return {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => {
      store.set(key, value)
    },
    removeItem: (key) => {
      store.delete(key)
    },
  }
}

const memoryStorage = createMemoryStorage()

function backing(): StorageLike {
  try {
    if (typeof globalThis.localStorage !== 'undefined') {
      return globalThis.localStorage
    }
  } catch {
    // 隐私模式等策略下访问会抛异常，按不可用处理
  }
  return memoryStorage
}

export const browserStorage = {
  /** 读取并反序列化；键不存在或内容损坏时返回 `null`（不抛异常）。 */
  read<T>(key: string): T | null {
    const raw = backing().getItem(key)
    if (raw === null) {
      return null
    }
    try {
      return JSON.parse(raw) as T
    } catch {
      return null
    }
  },

  write(key: string, value: unknown): void {
    backing().setItem(key, JSON.stringify(value))
  },

  remove(key: string): void {
    backing().removeItem(key)
  },
}
