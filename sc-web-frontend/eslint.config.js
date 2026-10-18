import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import vueParser from 'vue-eslint-parser'

/**
 * ESLint 扁平配置。
 *
 * 两个刻意的选择：
 *
 * 1. **启用类型感知规则**（`recommendedTypeChecked`）。只做语法层检查的 ESLint 与 `tsc`
 *    大量重叠、收益有限；真正补位的是类型感知规则，尤其是 `no-floating-promises`
 *    ——本项目前端全是异步调用（接口请求、SSE 流式问答），"忘了 await" 这类 bug
 *    类型系统查不出来，只能靠它拦。
 * 2. **Vue 侧用 `flat/essential` 而不是 `flat/recommended`**。`recommended` 含大量
 *    格式化规则（缩进、属性换行），那是 Prettier 的职责；本项目不引入 Prettier
 *    （避免新增依赖与全量重排），所以只启用能发现真实问题的 essential 规则集。
 *
 * 踩坑记录（TASK-103 实际遇到，都是"直接抛错"而非给出 lint 提示，很难定位）：
 *
 * - `recommendedTypeChecked` 会连纯 JS 配置文件一起检查，而 JS 文件不在 tsconfig 覆盖范围内、
 *   拿不到类型信息，类型感知规则会在其上抛错。必须对 JS 文件显式 `disableTypeChecked`
 *   （见下面最后一个配置块）。
 * - `.vue` 文件必须使用 `vue-eslint-parser`，并把内部 `<script>` 转交给 typescript-eslint 的
 *   parser（即 `parserOptions.parser`），同时开启 `projectService`。这两个键在下面的 .vue 块里
 *   整体给出，不依赖配置合并行为。
 * - 撰写本文件时，块注释里不要出现「星号紧跟斜杠」的路径通配写法：那三个字符会**提前终止注释**，
 *   导致后续内容被当作代码并报出莫名的语法错误。本文件第一版就因此报错。
 */

const TYPE_AWARE_RULES = {
  '@typescript-eslint/no-floating-promises': 'error',
  '@typescript-eslint/no-misused-promises': 'error',
  '@typescript-eslint/consistent-type-imports': 'error',
  // 单文件组件里 App.vue 是 Vue 生态的既定命名，不为该规则改名
  'vue/multi-word-component-names': 'off',
}

export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**'],
  },
  ...tseslint.configs.recommendedTypeChecked,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.ts', '*.config.ts'],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: TYPE_AWARE_RULES,
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
        extraFileExtensions: ['.vue'],
      },
    },
    rules: TYPE_AWARE_RULES,
  },
  {
    files: ['**/*.js'],
    extends: [tseslint.configs.disableTypeChecked],
  },
)
