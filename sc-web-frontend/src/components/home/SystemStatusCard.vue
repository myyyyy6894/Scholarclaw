<script setup lang="ts">
import type { Component } from 'vue'

import { Bot, Database, Layers, MessageSquare, Server, Zap } from '@lucide/vue'

import BaseCard from '@/components/ui/BaseCard.vue'
import ComingSoonBadge from '@/components/ui/ComingSoonBadge.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import type { PendingModule } from '@/views/home/use-home-data'

/**
 * 首页系统状态（P2）。
 *
 * 后端没有公开的浏览器侧状态接口，因此六行统一显示「暂未开发」；
 * **不得**写成“正常”，也不显示任何推断出的绿色状态。
 */
defineProps<{ state: PendingModule }>()

interface ServiceRow {
  name: string
  icon: Component
}

const SERVICES: ServiceRow[] = [
  { name: 'Java 后端', icon: Server },
  { name: 'Python AI', icon: Bot },
  { name: 'MySQL', icon: Database },
  { name: 'Redis', icon: Zap },
  { name: 'ChromaDB', icon: Layers },
  { name: 'RabbitMQ', icon: MessageSquare },
]
</script>

<template>
  <BaseCard title="系统状态">
    <template #actions>
      <TagBadge label="暂未开发" tone="neutral" />
    </template>

    <ul class="status-list">
      <li v-for="service in SERVICES" :key="service.name" class="status-row">
        <span class="status-row__icon">
          <component :is="service.icon" :size="15" :stroke-width="1.7" />
        </span>
        <span class="status-row__name">{{ service.name }}</span>
        <ComingSoonBadge :hint="state.hint" compact />
      </li>
    </ul>

    <p class="status-note">状态接口就绪后，这里会显示各服务的健康探测结果。</p>
  </BaseCard>
</template>

<style scoped>
.status-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 4px;
}

.status-row__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--sc-text-secondary);
  background: var(--sc-surface-muted);
}

.status-row__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-note {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--sc-border);
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.55;
}
</style>
