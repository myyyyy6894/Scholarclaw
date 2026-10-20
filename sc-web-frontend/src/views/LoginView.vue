<script setup lang="ts">
import { computed, reactive, ref } from 'vue'

import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { login, register, toAuthSession } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifySuccess } from '@/utils/notify'

/**
 * 登录 / 注册页（`06` TASK-205、`04` §4.1 `API-AUTH-001/002`）。
 *
 * 三条约定：
 * 1. 表单校验只做即时反馈，**真正的授权边界在后端**（`05` §4.4）；
 * 2. 登录成功后只把 token 与展示信息交给 store，绝不把整个响应或任何密钥落盘；
 * 3. 401 由 API client 统一清理会话；登录失败时本地本来就没有会话，不会误跳转。
 */

type AuthMode = 'login' | 'register'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const mode = ref<AuthMode>('login')
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,64}$/
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/

const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: USERNAME_PATTERN, message: '用户名需为 3~64 位字母、数字、下划线或连字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    ...(mode.value === 'register'
      ? [{ pattern: PASSWORD_PATTERN, message: '密码需为 8~64 位，且同时包含字母与数字', trigger: 'blur' }]
      : []),
  ],
  confirmPassword:
    mode.value === 'register'
      ? [
          { required: true, message: '请再次输入密码', trigger: 'blur' },
          {
            validator: (_rule: unknown, value: string, callback: (error?: string | Error) => void) => {
              if (value !== form.password) {
                callback(new Error('两次输入的密码不一致'))
                return
              }
              callback()
            },
            trigger: 'blur',
          },
        ]
      : [],
}))

const submitLabel = computed(() => (mode.value === 'login' ? '登录' : '注册并登录'))

function switchMode(next: AuthMode): void {
  if (mode.value === next) {
    return
  }
  mode.value = next
  form.password = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

function handleTabChange(name: string | number): void {
  if (name === 'login' || name === 'register') {
    switchMode(name)
  }
}

async function submit(): Promise<void> {
  if (submitting.value) {
    return
  }
  // 先置 busy 再等校验：表单回车与按钮点击可能在同一 tick 触发两次
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (valid !== true) {
      return
    }
    const response =
      mode.value === 'login'
        ? await login({ username: form.username.trim(), password: form.password })
        : await register({ username: form.username.trim(), password: form.password })
    auth.setSession(toAuthSession(response))
    notifySuccess(mode.value === 'login' ? '登录成功' : '注册成功，已自动登录')
    await router.replace(resolveRedirect())
  } catch (error) {
    notifyError(error)
  } finally {
    submitting.value = false
  }
}

/** 只接受站内绝对路径，避免 `redirect` 被用来做开放重定向。 */
function resolveRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/settings'
}

function handleEnter(): void {
  void submit()
}

function showRegister(): void {
  switchMode('register')
  ElMessage.info('注册后会自动登录，并引导你完成模型配置。')
}
</script>

<template>
  <div class="sc-auth">
    <el-card class="sc-auth__card" shadow="never">
      <template #header>
        <div class="sc-auth__header">
          <span class="sc-auth__title">ScholarClaw 智学爪</span>
          <span class="sc-auth__subtitle">课程知识库 · DDL 待办 · RAG 问答</span>
        </div>
      </template>

      <el-tabs :model-value="mode" class="sc-auth__tabs" @update:model-value="handleTabChange">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleEnter"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            autocomplete="username"
            placeholder="3~64 位字母、数字、下划线或连字符"
            @keyup.enter="handleEnter"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            placeholder="至少 8 位，需同时包含字母与数字"
            @keyup.enter="handleEnter"
          />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再次输入密码"
            @keyup.enter="handleEnter"
          />
        </el-form-item>

        <el-button
          class="sc-auth__submit"
          type="primary"
          size="large"
          :loading="submitting"
          @click="handleEnter"
        >
          {{ submitLabel }}
        </el-button>
      </el-form>

      <div class="sc-auth__footer">
        <template v-if="mode === 'login'">
          还没有账号？
          <el-link type="primary" :underline="false" @click="showRegister">立即注册</el-link>
        </template>
        <template v-else>
          已有账号？
          <el-link type="primary" :underline="false" @click="switchMode('login')">返回登录</el-link>
        </template>
      </div>

      <el-alert
        class="sc-auth__notice"
        type="info"
        :closable="false"
        show-icon
        title="密钥安全"
        description="登录凭据只用于换取访问令牌；模型 API Key 只在配置时提交，页面与浏览器存储都不会保存明文。"
      />
    </el-card>
  </div>
</template>

<style scoped>
.sc-auth {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 96px);
}

.sc-auth__card {
  width: min(440px, 100%);
}

.sc-auth__header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sc-auth__title {
  font-size: 18px;
  font-weight: 600;
}

.sc-auth__subtitle {
  color: #909399;
  font-size: 13px;
}

.sc-auth__tabs {
  margin-bottom: 8px;
}

.sc-auth__submit {
  width: 100%;
  margin-top: 8px;
}

.sc-auth__footer {
  margin-top: 16px;
  color: #606266;
  font-size: 14px;
  text-align: center;
}

.sc-auth__notice {
  margin-top: 16px;
}
</style>
