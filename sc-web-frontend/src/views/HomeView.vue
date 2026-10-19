<script setup lang="ts">
import { resolveApiBaseUrl } from '@/api/client'

/**
 * 工程骨架的落地页。
 *
 * 刻意不放"假功能"：不渲染空的教学卡片、不调用不存在的后端接口，
 * 只如实展示当前构建环境与各能力项的落地任务号，避免把"还没有的东西"呈现为已完成。
 */

const apiBaseUrl = resolveApiBaseUrl()
const mode = import.meta.env.MODE

const implemented = [
  'Vue 3 + TypeScript + Vite 工程与类型检查（vue-tsc）',
  '质量门禁 npm run check：类型检查 + ESLint（类型感知规则）+ 18 项单元测试',
  '路由骨架与鉴权守卫（重定向目标待 TASK-205 提供登录页）',
  '统一 API client：信封解包、错误分类、401 清理会话',
  '登录态管理（Pinia）与浏览器存储封装',
  '全局错误提示出口（Element Plus ElMessage）',
  '生产构建已由 sc-web-nginx 容器托管，并反代 /api 与 /webhooks（TASK-104）',
]

const upcoming = [
  { task: 'TASK-205', title: '登录、注册与个人设置（BYOK 模型配置）' },
  { task: 'TASK-404', title: '课程知识库管理视图（左侧栏 + 文档抽屉）' },
  { task: 'TASK-605', title: 'RAG 溯源问答主工作区（SSE 流式 + 引用高亮）' },
  { task: 'TASK-705', title: 'DDL 任务看板与待确认收件箱' },
]
</script>

<template>
  <div>
    <el-card class="sc-card-gap" shadow="never">
      <template #header>构建与环境信息</template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="业务接口前缀">
          {{ apiBaseUrl }}
        </el-descriptions-item>
        <el-descriptions-item label="构建模式">{{ mode }}</el-descriptions-item>
        <el-descriptions-item label="开发态代理">
          由 <code>VITE_DEV_PROXY_TARGET</code> 指定，仅绑定环回地址
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="sc-card-gap" shadow="never">
      <template #header>当前骨架已提供</template>
      <ul class="sc-list">
        <li v-for="item in implemented" :key="item">{{ item }}</li>
      </ul>
    </el-card>

    <el-card class="sc-card-gap" shadow="never">
      <template #header>待后续任务落地</template>
      <el-table :data="upcoming" size="small" :show-header="false">
        <el-table-column prop="task" width="120" />
        <el-table-column prop="title" />
      </el-table>
    </el-card>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="关于接口调用"
      description="本页不调用后端接口：Java 侧尚未落地任何 Web 业务接口（P2 起）。开发态请通过 Vite 代理把 /api 指向 Java 服务，生产态由 sc-web-nginx 反向代理。"
    />
  </div>
</template>

<style scoped>
.sc-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}
</style>
