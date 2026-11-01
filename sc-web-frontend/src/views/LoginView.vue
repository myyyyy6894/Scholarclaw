<script setup lang="ts">
import { computed, reactive, ref } from 'vue'

import { CalendarClock, Library, Sparkles } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { login, register, toAuthSession } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { notifyError, notifySuccess } from '@/utils/notify'
import { hasSeenGuide, markGuideSeen } from '@/utils/guide-flag'
import { firstVisitGuideTarget } from '@/views/guide/first-visit'

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
    await router.replace(resolveNextTarget())
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

/**
 * 登录后的落地页。
 *
 * 第一次登录/注册：先带用户看一遍使用指南（只跳一次，标记写在本地）；
 * 之后、或者用户本来就带着明确 redirect（比如被守卫踢回登录）时，按原逻辑走。
 */
function resolveNextTarget(): string {
  const redirect = route.query.redirect
  const hasRedirect = typeof redirect === 'string' && redirect.startsWith('/')
  const guideTarget = firstVisitGuideTarget(hasSeenGuide(), hasRedirect, route.path)
  if (guideTarget === null) {
    return resolveRedirect()
  }
  markGuideSeen()
  return guideTarget
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
  <div class="auth-page">
    <section class="auth-brand">
      <div class="auth-brandline">
        <span class="auth-brandline__mark">
          <img src="/illustrations/logo.svg" alt="" />
        </span>
        <span class="auth-brandline__text">
          <strong>ScholarClaw</strong>
          <small>智 学 爪</small>
        </span>
      </div>

      <div class="auth-brand__copy">
        <h1>让学习更智能<br />让知识有回声</h1>
        <p>
          基于 AI 的学习助手，帮助你在课程知识库中高效检索、规划 DDL，
          并随时获得有依据的回答。
        </p>
      </div>

      <ul class="auth-features">
        <li>
          <span class="auth-features__icon">
            <CalendarClock :size="18" :stroke-width="1.8" />
          </span>
          <span class="auth-features__text">
            <strong>DDL 雷达</strong>
            <small>智能识别 · 及时提醒 · 任务管理</small>
          </span>
        </li>
        <li>
          <span class="auth-features__icon">
            <Library :size="18" :stroke-width="1.8" />
          </span>
          <span class="auth-features__text">
            <strong>课程知识库</strong>
            <small>上传课件 · 解析索引 · 溯源问答</small>
          </span>
        </li>
        <li>
          <span class="auth-features__icon">
            <Sparkles :size="18" :stroke-width="1.8" />
          </span>
          <span class="auth-features__text">
            <strong>AI 学习助手</strong>
            <small>基于课程知识 · 回答附引用来源</small>
          </span>
        </li>
      </ul>

      <div class="auth-brand__quote">
        <span>—</span>
        <p>学习不是一场冲刺<br />而是一段持续积累的旅程。</p>
      </div>

      <div class="auth-brand__art">
        <img src="/illustrations/hero-student.webp" alt="" />
      </div>
    </section>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="auth-card__brand">
          <span class="auth-brandline__mark">
            <img src="/illustrations/logo.svg" alt="" />
          </span>
          <span class="auth-brandline__text">
            <strong>ScholarClaw</strong>
            <small>智 学 爪</small>
          </span>
        </div>

        <header class="auth-card__header">
          <span class="auth-card__eyebrow">
            {{ mode === 'login' ? 'WELCOME BACK' : 'CREATE ACCOUNT' }}
          </span>
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
          <p>
            {{
              mode === 'login'
                ? '登录后继续使用课程知识库、DDL 提醒与 AI 问答。'
                : '注册后会自动登录，并引导你完成模型配置。'
            }}
          </p>
        </header>

        <el-tabs :model-value="mode" class="auth-card__tabs" @update:model-value="handleTabChange">
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
            class="auth-card__submit"
            type="primary"
            size="large"
            :loading="submitting"
            @click="handleEnter"
          >
            {{ submitLabel }}
          </el-button>
        </el-form>

        <div class="auth-card__footer">
          <template v-if="mode === 'login'">
            还没有账号？
            <el-link type="primary" :underline="false" @click="showRegister">立即注册</el-link>
          </template>
          <template v-else>
            已有账号？
            <el-link type="primary" :underline="false" @click="switchMode('login')">
              返回登录
            </el-link>
          </template>
        </div>

        <el-alert
          class="auth-card__notice"
          type="info"
          :closable="false"
          show-icon
          title="密钥安全"
          description="登录凭据只用于换取访问令牌；模型 API Key 只在配置时提交，页面与浏览器存储都不会保存明文。"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.auth-page {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(360px, 440px);
  gap: 20px;
  align-items: stretch;
  width: 100%;
  max-width: var(--sc-narrow-max-width);
  min-height: calc(100dvh - var(--sc-topbar-height) - 52px);
  margin: 0 auto;
}

.auth-brand {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 30px;
  overflow: hidden;
  border: 1px solid var(--sc-border);
  border-radius: 18px;
  background: linear-gradient(
    160deg,
    var(--sc-primary-soft) 0%,
    var(--sc-surface) 55%,
    var(--sc-accent-soft) 100%
  );
  box-shadow: var(--sc-shadow);
}

.auth-brandline {
  display: flex;
  align-items: center;
  gap: 10px;
}

.auth-brandline__mark {
  display: block;
  flex: none;
  width: 38px;
  height: 38px;
  overflow: hidden;
}

.auth-brandline__mark img {
  display: block;
  width: auto;
  height: 38px;
}

.auth-brandline__text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.auth-brandline__text strong {
  color: var(--sc-text);
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.2px;
}

.auth-brandline__text small {
  color: var(--sc-text-placeholder);
  font-size: 10px;
  letter-spacing: 2px;
}

.auth-brand__copy {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 440px;
}

.auth-brand__copy h1 {
  color: var(--sc-text);
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -0.6px;
  line-height: 1.35;
}

.auth-brand__copy p {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.8;
}

.auth-features {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
}

.auth-features li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--sc-border);
  border-radius: 12px;
  background: var(--sc-surface);
}

.auth-features__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.auth-features__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.auth-features__text strong {
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
}

.auth-features__text small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.auth-brand__quote {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 14px;
  border-top: 1px solid var(--sc-border);
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.auth-brand__quote span {
  color: var(--sc-primary);
  font-weight: 700;
}

.auth-brand__art {
  margin-top: 0;
  overflow: hidden;
  border-radius: 14px;
  background: #eaf4ff;
}

.auth-brand__art img {
  display: block;
  width: 100%;
  height: 170px;
  object-fit: cover;
}

.auth-panel {
  display: flex;
  align-items: center;
  min-width: 0;
}

.auth-card {
  width: 100%;
  padding: 28px;
  border: 1px solid var(--sc-border);
  border-radius: 18px;
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
}

.auth-card__brand {
  display: none;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.auth-card__header {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.auth-card__eyebrow {
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
  font-weight: 700;
  letter-spacing: 1.2px;
}

.auth-card__header h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
}

.auth-card__header p {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.6;
}

.auth-card__tabs {
  margin: 12px 0 4px;
}

.auth-card__submit {
  width: 100%;
  margin-top: 6px;
}

.auth-card__footer {
  margin-top: 16px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  text-align: center;
}

.auth-card__notice {
  margin-top: 16px;
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
    max-width: 520px;
    min-height: 0;
  }

  .auth-brand {
    display: none;
  }

  .auth-card__brand {
    display: flex;
  }

  .auth-panel {
    align-items: flex-start;
  }
}

@media (max-width: 639px) {
  .auth-card {
    padding: 20px;
  }
}
</style>
