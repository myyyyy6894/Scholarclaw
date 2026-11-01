<script setup lang="ts">
import { ArrowRight, Sun } from '@lucide/vue'

/**
 * 首页欢迎横幅（P2）。
 *
 * 插画使用 `hero-student.webp`（原图 1495×420，压缩后约 91 KB）。
 * 文案左侧始终压在主题色遮罩上，深色模式也能保持对比度。
 */
defineProps<{
  greeting: string
  userName: string
  dateLabel: string
  authenticated: boolean
}>()
</script>

<template>
  <section class="hero-banner">
    <div class="hero-banner__art">
      <img src="/illustrations/hero-student.webp" alt="" />
    </div>
    <div class="hero-banner__overlay" aria-hidden="true" />

    <div class="hero-banner__content">
      <span class="hero-banner__date">
        <Sun :size="14" :stroke-width="1.8" />
        {{ dateLabel }}
      </span>
      <h2>{{ greeting }}，{{ userName }}</h2>
      <p>今天也要加油学习呀！你的专属 AI 学术与工程助手，随时为你服务。</p>
      <div class="hero-banner__actions">
        <router-link
          class="hero-banner__primary"
          :to="{ name: authenticated ? 'rag' : 'login' }"
        >
          {{ authenticated ? '开始提问' : '登录 / 注册' }}
          <ArrowRight :size="15" :stroke-width="1.9" />
        </router-link>
        <router-link v-if="authenticated" class="hero-banner__secondary" :to="{ name: 'knowledge' }">
          上传课件
        </router-link>
      </div>
    </div>
  </section>
</template>

<style scoped>
.hero-banner {
  position: relative;
  min-height: 190px;
  overflow: hidden;
  border: 1px solid var(--sc-border);
  border-radius: 16px;
  background: #eaf4ff;
  box-shadow: var(--sc-shadow);
}

.hero-banner__art {
  position: absolute;
  inset: 0 0 0 auto;
  width: 58%;
}

.hero-banner__art img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  -webkit-mask-image: linear-gradient(90deg, transparent 0%, #000 22%);
  mask-image: linear-gradient(90deg, transparent 0%, #000 22%);
}

.hero-banner__overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    var(--sc-surface) 0%,
    var(--sc-surface) 34%,
    color-mix(in srgb, var(--sc-surface) 72%, transparent) 58%,
    transparent 100%
  );
}

.hero-banner__content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  max-width: 58%;
  padding: 28px 30px;
}

.hero-banner__date {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px;
  border-radius: var(--sc-radius-pill);
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  font-size: var(--sc-font-hint);
  font-weight: 500;
}

.hero-banner__content h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
  line-height: 1.3;
}

.hero-banner__content p {
  max-width: 460px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.hero-banner__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.hero-banner__primary,
.hero-banner__secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  border-radius: var(--sc-radius-button);
  font-size: var(--sc-font-caption);
  font-weight: 500;
  text-decoration: none;
  transition: background-color 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.hero-banner__primary {
  color: #fff;
  background: var(--sc-primary);
  box-shadow: 0 8px 18px rgba(79, 140, 255, 0.28);
}

.hero-banner__primary:hover {
  background: var(--sc-primary-strong);
  color: #fff;
  transform: translateY(-1px);
}

.hero-banner__secondary {
  border: 1px solid var(--sc-border);
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
}

.hero-banner__secondary:hover {
  border-color: var(--sc-primary);
  color: var(--sc-primary);
}

@media (max-width: 900px) {
  .hero-banner__art {
    width: 72%;
    opacity: 0.7;
  }

  .hero-banner__content {
    max-width: 80%;
    padding: 22px;
  }

  .hero-banner__content h2 {
    font-size: 21px;
  }
}

@media (max-width: 639px) {
  .hero-banner {
    min-height: 176px;
  }

  .hero-banner__art {
    width: 100%;
    opacity: 0.45;
  }

  .hero-banner__overlay {
    background: linear-gradient(
      180deg,
      var(--sc-surface) 0%,
      color-mix(in srgb, var(--sc-surface) 70%, transparent) 70%,
      transparent 100%
    );
  }

  .hero-banner__content {
    max-width: 100%;
    padding: 20px;
  }
}
</style>
