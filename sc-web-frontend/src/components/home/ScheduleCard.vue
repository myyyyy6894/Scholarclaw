<script setup lang="ts">
import BaseCard from '@/components/ui/BaseCard.vue'
import ComingSoonBadge from '@/components/ui/ComingSoonBadge.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import type { PendingModule } from '@/views/home/use-home-data'

/**
 * 首页今日安排（P2）。
 *
 * 课表数据后端尚未提供；这里保留完整卡片与插画，但只显示「暂未开发」，
 * 不编造任何课程、时间或地点。
 */
defineProps<{
  state: PendingModule
  dateLabel: string
}>()
</script>

<template>
  <BaseCard title="今日安排" :subtitle="dateLabel">
    <template #actions>
      <TagBadge label="今天" tone="primary" />
    </template>

    <div class="schedule">
      <div class="schedule__art">
        <img src="/illustrations/status-schedule.png" alt="" loading="lazy" />
      </div>
      <ComingSoonBadge :hint="state.hint" />
      <p>课表功能暂未开发；接入后这里会显示今天的课程与时间安排。</p>
    </div>
  </BaseCard>
</template>

<style scoped>
.schedule {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

.schedule__art {
  display: grid;
  place-items: center;
  width: 100%;
  max-width: 190px;
  padding: 8px;
  border-radius: var(--sc-radius-card);
  background: #f1f6ff;
}

.schedule__art img {
  display: block;
  width: 100%;
  max-height: 96px;
  object-fit: contain;
}

.schedule p {
  max-width: 260px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.6;
}
</style>
