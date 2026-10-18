<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function backToHome(): void {
  void router.push({ name: 'home' })
}
</script>

<template>
  <el-result icon="warning" title="404" sub-title="页面不存在或已被移除。">
    <template #extra>
      <el-button type="primary" @click="backToHome">返回工作台</el-button>
    </template>
  </el-result>
</template>
