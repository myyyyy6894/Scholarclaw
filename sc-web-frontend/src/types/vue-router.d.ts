import 'vue-router'

/**
 * `RouteMeta` 的模块增强。
 *
 * ⚠️ 这个文件顶部的 `import 'vue-router'` **不能省略**：只有本文件被当作模块时，
 * 下面的 `declare module 'vue-router'` 才是「模块增强」（接口合并）；
 * 若放在没有 import/export 的全局 `env.d.ts` 里，它会被当成「环境模块声明」，
 * 直接把 `vue-router` 的真实导出**整个覆盖掉**，届时 `createRouter`、`useRouter`
 * 等全部报 "has no exported member"，本项目在 TASK-103 就踩过这个坑。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 侧栏/浏览器标题用的页面名称。 */
    title?: string
    /**
     * 是否要求已登录。
     *
     * **注意（`05` §4.4）**：路由守卫只是前端 UX，绝不构成权限边界——
     * 真正的授权判定始终由 Java 后端按其 `SecurityContext` 执行。
     */
    requiresAuth?: boolean
  }
}
