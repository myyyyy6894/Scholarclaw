<script setup lang="ts">
import { computed } from 'vue'

import { BookOpen, MessageCircle, Rocket, Settings2, Sparkles } from '@lucide/vue'

import BaseCard from '@/components/ui/BaseCard.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import {
  copyOperatorQq,
  normalizeOperatorQq,
  normalizeProjectUrl,
  readRuntimeBotQq,
  readRuntimeOperatorQq,
  readRuntimeProjectUrl,
  resolveOperatorQq,
} from '@/utils/operator-contact'
import { notifySuccess, notifyWarning } from '@/utils/notify'

/**
 * 使用指南（`/guide`）。
 *
 * 首次登录后自动打开一次；侧栏「帮助与反馈」也可随时进入。
 * 这里集中解释：各功能做什么、模型怎么配（对话 Key 自备、向量模型由部署方提供）、
 * IM 绑定为什么可选、常见问题、以及联系方式。
 *
 * 之所以集中到一页：设置页里原来散落的说明（向量模型统一提供、尚未配置模型、
 * IM 绑定可选、关于）在表单中间打断填写，第一次使用的人反而看不清全貌。
 */
const operatorQq = resolveOperatorQq(readRuntimeOperatorQq())
const projectUrl = normalizeProjectUrl(readRuntimeProjectUrl())
/** 发提醒的机器人账号，与维护者 QQ 是两个号。 */
const botQq = normalizeOperatorQq(readRuntimeBotQq())
const buildMode = import.meta.env.MODE

const hasContact = computed(() => Boolean(operatorQq || projectUrl))

async function copyBot(): Promise<void> {
  if (await copyOperatorQq(botQq)) {
    notifySuccess('机器人 QQ 已复制。')
    return
  }
  notifyWarning('复制失败，请手动选择号码复制。')
}

async function copyQq(): Promise<void> {
  if (await copyOperatorQq(operatorQq)) {
    notifySuccess('QQ 号已复制。')
    return
  }
  notifyWarning('复制失败，请手动选择号码复制。')
}

const FEATURES = [
  {
    name: '首页',
    desc: '今日待办、课程概览、最近学习、DDL 日历与站内通知，一屏看当天要做什么。',
  },
  {
    name: '我的课程',
    desc: '每门课是一个独立空间：自己的资料、自己的向量索引，互不干扰。',
  },
  {
    name: '知识库',
    desc: '上传课件（PDF / MD / TXT，单文件 ≤ 50 MB），后台自动解析、切块、入库。解析失败可以重试或重建索引。',
  },
  {
    name: 'AI 学习',
    desc: '针对当前课程提问，回答会附带引用片段，能点回课件原文；知识库里没有依据时直接拒答，不会编造。',
  },
  {
    name: 'DDL 任务',
    desc: '用自然语言描述截止时间（支持"下周三""月底"），系统解析成结构化任务；可在看板与日历间切换。',
  },
  {
    name: '通知',
    desc: '截止前 24 小时、6 小时与逾期时提醒，可设置免打扰时段。',
  },
  {
    name: '设置',
    desc: '填写自己的模型 Key、修改偏好、销毁密钥、注销账号。',
  },
]

const FAQ = [
  {
    q: '课件解析很久没好？',
    a: '服务器用 CPU 计算向量，没有 GPU 加速。一份几十页的课件通常需要几分钟到十几分钟，页数越多越慢；同时传多份会排队。状态显示「解析中」就是还在处理，请耐心等待。',
  },
  {
    q: '解析一直失败怎么办？',
    a: '在知识库对那份课件点「重试」；仍然失败时点「重建索引」。若持续失败，可能是 PDF 是扫描件（图片文字），当前版本不做 OCR。',
  },
  {
    q: '提问回答"没有找到相关内容"？',
    a: '通常是课件还没解析完成（状态不是「就绪」），或问题确实超出课件范围。换一种问法、或补充相关课件后再试。',
  },
  {
    q: '可以在手机上用吗？',
    a: '可以，页面自适应手机。上传大文件建议在电脑上操作。',
  },
]
</script>

<template>
  <div class="guide">
    <BaseCard class="guide-hero">
      <div class="guide-hero__body">
        <span class="guide-hero__icon"><Sparkles :size="22" :stroke-width="1.8" /></span>
        <div>
          <h2>三分钟上手 ScholarClaw</h2>
          <p>
            把你自己的课件变成能问答的知识库，再让它帮你盯住作业截止时间。
            下面是每个功能做什么、以及第一次使用要准备什么。
          </p>
        </div>
      </div>

      <ol class="guide-steps">
        <li>
          <strong>1</strong>
          <div>
            <h3>注册账号</h3>
            <p>用户名 + 密码即可，不需要邮箱验证。</p>
          </div>
        </li>
        <li>
          <strong>2</strong>
          <div>
            <h3>填写对话模型 Key</h3>
            <p>
              去「设置 → BYOK 模型配置」，填一个对话模型 API Key（智谱、DeepSeek 或任意 OpenAI 兼容服务都可以，
              注册后大多有免费额度）。向量模型已经由部署方提供，<b>不需要你配置</b>。
            </p>
          </div>
        </li>
        <li>
          <strong>3</strong>
          <div>
            <h3>建课程、传课件、开始提问</h3>
            <p>「我的课程」新建课程 → 「知识库」上传课件 → 等状态变成「就绪」→ 「AI 学习」提问。</p>
          </div>
        </li>
      </ol>
    </BaseCard>

    <BaseCard>
      <template #header>
        <div class="guide-heading">
          <span class="guide-heading__icon"><BookOpen :size="17" :stroke-width="1.8" /></span>
          <h3>功能一览</h3>
        </div>
      </template>
      <dl class="guide-features">
        <div v-for="item in FEATURES" :key="item.name" class="guide-feature">
          <dt>{{ item.name }}</dt>
          <dd>{{ item.desc }}</dd>
        </div>
      </dl>
    </BaseCard>

    <BaseCard>
      <template #header>
        <div class="guide-heading">
          <span class="guide-heading__icon"><Settings2 :size="17" :stroke-width="1.8" /></span>
          <h3>模型说明</h3>
        </div>
      </template>

      <div class="guide-block">
        <h4>对话模型（需要你自己准备）</h4>
        <p>提问、DDL 提取都靠它。以 DeepSeek 为例：</p>
        <ul>
          <li>到 DeepSeek 开放平台注册并创建 API Key</li>
          <li>设置页填：供应商 <code>DEEPSEEK</code>、Base URL <code>https://api.deepseek.com/v1</code>、模型名 <code>deepseek-chat</code></li>
          <li>点「测试对话模型」，通过后保存</li>
        </ul>
        <p class="guide-note">Key 只保存在服务端并加密存储，页面只会显示掩码。</p>
      </div>

      <div class="guide-block">
        <h4>
          向量模型（部署方统一提供）
          <TagBadge label="平台默认" tone="primary" />
        </h4>
        <p>
          课件要转成向量才能被检索。本站在服务器上直接跑了一个小模型（<code>nomic-embed-text</code>，768 维），
          所有用户共用，所以你不必申请 Embedding Key。
        </p>
        <p class="guide-warn">
          ⚠️ 服务器是 2 核 CPU、纯 CPU 推理，<b>解析速度有限</b>：几十页的课件要几分钟到十几分钟，
          多份课件会排队。这是算力限制，不是故障。
        </p>
        <p>
          如果你有自己的 Embedding 服务（比如 SiliconFlow 的 <code>BAAI/bge-m3</code>），可以在设置页
          「默认 Embedding 模型」里覆盖，速度与效果都会更好。注意：<b>换向量模型后，已有课件需要重建索引</b>，
          否则新旧向量不兼容，问答会对不上。
        </p>
      </div>

      <div class="guide-block">
        <h4>QQ 提醒（可选）</h4>
        <p>
          截止提醒默认发到站内通知；如果想让机器人直接私聊提醒你，需要两步：
          先<b>加机器人 QQ 为好友</b>，再到「设置 → 个人偏好」里绑定你自己的 QQ 号。
          不绑定也完全不影响网页端功能。
        </p>
        <div v-if="botQq" class="guide-contact__row">
          <span class="guide-contact__label">机器人 QQ</span>
          <strong class="guide-contact__value">{{ botQq }}</strong>
          <el-button size="small" @click="copyBot">复制</el-button>
        </div>
        <p v-if="operatorQq" class="guide-note">
          机器人只负责发提醒；使用问题请找下方「维护者 QQ」反馈，两者不是同一个号。
        </p>
      </div>
    </BaseCard>

    <BaseCard>
      <template #header>
        <div class="guide-heading">
          <span class="guide-heading__icon"><MessageCircle :size="17" :stroke-width="1.8" /></span>
          <h3>常见问题</h3>
        </div>
      </template>
      <el-collapse class="guide-faq">
        <el-collapse-item v-for="item in FAQ" :key="item.q" :title="item.q">
          <p>{{ item.a }}</p>
        </el-collapse-item>
      </el-collapse>
    </BaseCard>

    <BaseCard v-if="hasContact">
      <template #header>
        <div class="guide-heading">
          <span class="guide-heading__icon"><Rocket :size="17" :stroke-width="1.8" /></span>
          <h3>联系与反馈</h3>
        </div>
      </template>
      <div class="guide-contact">
        <div v-if="operatorQq" class="guide-contact__row">
          <span class="guide-contact__label">维护者 QQ</span>
          <strong class="guide-contact__value">{{ operatorQq }}</strong>
          <el-button size="small" @click="copyQq">复制</el-button>
        </div>
        <div v-if="projectUrl" class="guide-contact__row">
          <span class="guide-contact__label">项目地址</span>
          <a :href="projectUrl" target="_blank" rel="noopener noreferrer">{{ projectUrl }}</a>
        </div>
        <p class="guide-note">遇到问题请说明：用的哪个功能、报错文字或错误码、大约什么时间。</p>
      </div>
    </BaseCard>

    <BaseCard>
      <template #header>
        <div class="guide-heading">
          <span class="guide-heading__icon"><BookOpen :size="17" :stroke-width="1.8" /></span>
          <h3>关于</h3>
        </div>
      </template>
      <p class="guide-note">
        构建模式 <code>{{ buildMode }}</code>。当前版本已实现：课程空间、知识库解析、溯源问答、DDL 提取与看板、站内通知、BYOK 模型配置。
        课程表、全局搜索、公开系统状态接口尚未实现，界面上对应位置会标注「暂未开发」。
      </p>
    </BaseCard>
  </div>
</template>

<style scoped>
.guide {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  max-width: var(--sc-narrow-max-width);
  margin: 0 auto;
}

.guide-hero__body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.guide-hero__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.guide-hero h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: 600;
}

.guide-hero__body p {
  margin-top: 6px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.guide-steps {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 20px 0 0;
  padding: 0;
  list-style: none;
}

.guide-steps li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.guide-steps strong {
  display: grid;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  background: var(--sc-primary);
  font-size: 13px;
}

.guide-steps h3 {
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
}

.guide-steps p {
  margin-top: 4px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.guide-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.guide-heading__icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.guide-heading h3 {
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
}

.guide-features {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
}

.guide-feature {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
}

.guide-feature dt {
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.guide-feature dd {
  margin: 0;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.guide-block + .guide-block {
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--sc-border);
}

.guide-block h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.guide-block p,
.guide-block li {
  margin-top: 8px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.75;
}

.guide-block ul {
  margin: 6px 0 0;
  padding-left: 20px;
}

.guide-note {
  color: var(--sc-text-placeholder) !important;
  font-size: var(--sc-font-hint) !important;
}

.guide-warn {
  padding: 10px 12px;
  border-radius: var(--sc-radius-button);
  background: var(--sc-warning-soft, #fff7e6);
  color: var(--sc-warning-text, #8a5a00) !important;
}

.guide-faq :deep(.el-collapse-item__header) {
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
}

.guide-faq p {
  margin: 0;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.75;
}

.guide-contact {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.guide-contact__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.guide-contact__label {
  min-width: 84px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.guide-contact__value {
  color: var(--sc-text);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
}

.guide-contact a {
  color: var(--sc-primary);
  word-break: break-all;
}

@media (max-width: 639px) {
  .guide-feature {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
