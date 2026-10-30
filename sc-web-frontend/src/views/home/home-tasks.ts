/** 首页能力清单：已实现与待落地任务必须互斥，避免把已完成任务再次标为 upcoming。 */

export interface UpcomingTask {
  task: string
  title: string
}

export const IMPLEMENTED_CAPABILITIES = [
  'Vue 3 + TypeScript + Vite 工程与类型检查（vue-tsc）',
  '质量门禁 npm run check：类型检查 + ESLint（类型感知规则）+ 单元测试',
  '登录 / 注册页（API-AUTH-001/002）与个人设置页（API-USER-001~005）',
  '路由守卫与全局 401 处理（会话失效自动回到登录页）',
  'BYOK 模型配置表单：连通性探测、保存状态、密钥掩码展示',
  '课程空间管理：列表、新建、切换 Embedding 模型、删除（TASK-401）',
  '课程知识库：课程切换、课件列表、上传与解析状态展示（TASK-404）',
  'RAG 溯源问答工作台：SSE 流式渲染、引用卡片、历史分页（TASK-605）',
  'DDL 待办工作台：通知提取、待确认收件箱、变更草案决策与任务闭环（TASK-705）',
  'Single Agent Router：斜杠指令与自然语言任务路由（TASK-706）',
  '站内通知中心：未读角标、分页查询、标记已读与 IM 失败保底（TASK-802）',
  'OneBot 11 入站适配：私聊/群 @ 文本、单层转发、文件课件与去重（TASK-803）',
  'IM 出站限流与降级：令牌桶、抖动、重试与仅 Web 保底（TASK-804）',
  'DDL 定时提醒：T-24h / T-6h / 逾期 / 跟进与免打扰（TASK-801、TASK-805）',
  '统一 API client：信封解包、错误分类、401 清理会话',
  '登录态管理（Pinia）与浏览器存储封装（只存令牌，不存密钥）',
  '全局错误提示出口（Element Plus ElMessage）',
  '生产构建已由 sc-web-nginx 容器托管，并反代 /api 与 /webhooks（TASK-104）',
]

export const UPCOMING_TASKS: UpcomingTask[] = [
  { task: 'TASK-901~906', title: 'P9：清理补偿、安全回归、性能基线与 MVP 验收' },
]
