<script setup lang="ts">
import { computed } from 'vue'

import { resolveApiBaseUrl } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { IMPLEMENTED_CAPABILITIES, UPCOMING_TASKS } from '@/views/home/home-tasks'

/**
 * 工程骨架的落地页。
 *
 * 刻意不放"假功能"：不渲染空的教学卡片、不调用不存在的后端接口，
 * 只如实展示当前构建环境与各能力项的落地任务号，避免把"还没有的东西"呈现为已完成。
 */

const apiBaseUrl = resolveApiBaseUrl()
const mode = import.meta.env.MODE
const auth = useAuthStore()

const primaryAction = computed(() =>
  auth.isAuthenticated
    ? { label: '前往模型配置', to: { name: 'settings' } }
    : { label: '登录 / 注册', to: { name: 'login' } },
)

const implemented = IMPLEMENTED_CAPABILITIES
const upcoming = UPCOMING_TASKS
</script>

<template>
  <div>
    <el-card class="sc-card-gap" shadow="never">
      <div class="sc-home__cta">
        <div>
          <div class="sc-home__cta-title">欢迎使用 ScholarClaw 智学爪</div>
          <div class="sc-home__cta-subtitle">
            {{ auth.isAuthenticated ? `当前登录：${auth.displayName}` : '登录后即可配置 BYOK 模型。' }}
          </div>
        </div>
        <router-link :to="primaryAction.to">
          <el-button type="primary">{{ primaryAction.label }}</el-button>
        </router-link>
      </div>
    </el-card>

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
      description="登录、注册与 BYOK 配置已接入真实后端接口；开发态通过 Vite 代理把 /api 指向 Java 服务，生产态由 sc-web-nginx 反向代理。"
    />
  </div>
</template>

<style scoped>
.sc-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}

.sc-home__cta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.sc-home__cta-title {
  font-size: 18px;
  font-weight: 600;
}

.sc-home__cta-subtitle {
  margin-top: 4px;
  color: #909399;
  font-size: 14px;
}
</style>
