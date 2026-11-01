<script setup lang="ts">
import { computed } from 'vue'

import { useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'

import { markNotificationsRead } from '@/api/notifications'
import type { NotificationItem } from '@/api/notifications'
import DdlCalendar from '@/components/home/DdlCalendar.vue'
import HeroBanner from '@/components/home/HeroBanner.vue'
import MyCourses from '@/components/home/MyCourses.vue'
import NoticeList from '@/components/home/NoticeList.vue'
import RecentChats from '@/components/home/RecentChats.vue'
import RecentDocs from '@/components/home/RecentDocs.vue'
import TodayTasks from '@/components/home/TodayTasks.vue'
import QuickActionCard from '@/components/ui/QuickActionCard.vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { notifyError } from '@/utils/notify'
import { useHomeData } from '@/views/home/use-home-data'
import { notificationTarget } from '@/views/notifications/notification-center'

/**
 * 首页 Dashboard（P2）。
 *
 * 参考图 `docs/ui_picture/首页.png` / `首页图片.png`：
 * 左侧主区（Hero、快捷入口、今日待办/课程、最近学习/问答），右侧栏（DDL 日历、通知）。
 *
 * 数据全部来自 `useHomeData`：有接口的一律接真实数据；未登录时各模块显示「登录后查看」，
 * 不写假状态、不留「暂未开发」占位。
 */
interface QuickAction {
  title: string
  description: string
  image: string
  to: RouteLocationRaw
  tone: 'blue' | 'green' | 'purple' | 'orange' | 'pink'
}

const QUICK_ACTIONS: QuickAction[] = [
  {
    title: 'AI 学习',
    description: '基于知识库的问答',
    image: '/illustrations/feature-chat.png',
    to: { name: 'rag' },
    tone: 'blue',
  },
  {
    title: '知识库',
    description: '上传课件 · 检索引用',
    image: '/illustrations/feature-knowledge.png',
    to: { name: 'knowledge' },
    tone: 'green',
  },
  {
    title: '我的课程',
    description: '课程管理 · 资料绑定',
    image: '/illustrations/feature-courses.png',
    to: { name: 'courses' },
    tone: 'purple',
  },
  {
    title: 'DDL 任务',
    description: '待办管理 · 状态跟踪',
    image: '/illustrations/feature-ddl.png',
    to: { name: 'ddl' },
    tone: 'orange',
  },
  {
    title: '上传课件',
    description: 'PDF / MD / TXT',
    image: '/illustrations/status-loading.png',
    to: { name: 'knowledge' },
    tone: 'pink',
  },
]

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

const {
  coursesState,
  boardState,
  todayTasksState,
  noticesState,
  recentDocsState,
  recentChatsState,
  courseDdlCounts,
  selectedCourse,
  chatPairs,
  greeting,
  dateLabel,
  todayIso,
  nowMs,
  unreadCount,
  retryCourses,
  retryBoard,
  retryNotifications,
  retryContent,
} = useHomeData()

const calendarTasks = computed(() => {
  const board = boardState.value.data
  return [
    ...board.pending_inbox_tasks,
    ...board.active_tasks,
    ...board.completed_tasks,
  ]
})
const calendarLoading = computed(() => boardState.value.status === 'loading')
const calendarError = computed(() =>
  boardState.value.status === 'error' ? boardState.value.error : '',
)
const courseName = computed(() => selectedCourse.value?.course_name ?? null)
const authenticated = computed(() => auth.isAuthenticated)

function onRetryCourses(): void {
  void retryCourses()
}

function onRetryBoard(): void {
  void retryBoard()
}

function onRetryNotifications(): void {
  void retryNotifications()
}

function onRetryContent(): void {
  void retryContent()
}

function onViewAllNotifications(): void {
  ui.openNotificationDrawer()
}

function onNoticeOpen(item: NotificationItem): void {
  void openNotice(item)
}

async function openNotice(item: NotificationItem): Promise<void> {
  try {
    if (item.read_status === 'UNREAD') {
      await markNotificationsRead({
        mark_all: false,
        notification_ids: [item.notification_id],
      })
      await retryNotifications()
    }
  } catch (error) {
    notifyError(error)
  } finally {
    await router.push({ name: notificationTarget(item.biz_type) })
  }
}
</script>

<template>
  <div class="home">
    <div class="home__main">
      <HeroBanner
        :greeting="greeting"
        :user-name="auth.displayName"
        :date-label="dateLabel"
        :authenticated="authenticated"
      />

      <section class="home__quick" aria-label="快捷入口">
        <QuickActionCard
          v-for="action in QUICK_ACTIONS"
          :key="action.title"
          :title="action.title"
          :description="action.description"
          :image="action.image"
          :to="action.to"
          :tone="action.tone"
        />
      </section>

      <div class="home__grid">
        <TodayTasks
          :state="todayTasksState"
          :now-ms="nowMs"
          :authenticated="authenticated"
          @retry="onRetryBoard"
        />
        <MyCourses
          :state="coursesState"
          :ddl-counts="courseDdlCounts"
          :authenticated="authenticated"
          @retry="onRetryCourses"
        />
      </div>

      <div class="home__grid">
        <RecentDocs
          :state="recentDocsState"
          :course-name="courseName"
          :now-ms="nowMs"
          :authenticated="authenticated"
          @retry="onRetryContent"
        />
        <RecentChats
          :state="recentChatsState"
          :pairs="chatPairs"
          :course-name="courseName"
          :now-ms="nowMs"
          :authenticated="authenticated"
          @retry="onRetryContent"
        />
      </div>
    </div>

    <aside class="home__rail">
      <DdlCalendar
        :tasks="calendarTasks"
        :today-iso="todayIso"
        :loading="calendarLoading"
        :error="calendarError"
        :authenticated="authenticated"
      />

      <NoticeList
        :state="noticesState"
        :unread-count="unreadCount"
        :authenticated="authenticated"
        @retry="onRetryNotifications"
        @open="onNoticeOpen"
        @view-all="onViewAllNotifications"
      />
    </aside>
  </div>
</template>

<style scoped>
.home {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--sc-card-gap);
  align-items: start;
}

.home__main {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  min-width: 0;
}

.home__quick {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.home__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sc-card-gap);
  align-items: start;
}

.home__rail {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  min-width: 0;
}

@media (max-width: 1279px) {
  .home {
    grid-template-columns: 1fr;
  }

  .home__rail {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

@media (max-width: 1023px) {
  .home__quick {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .home__grid {
    grid-template-columns: 1fr;
  }

  .home__rail {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .home__quick {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  /* 5 个入口在双列布局下最后一个占满整行，避免留下半行空白。 */
  .home__quick > :last-child:nth-child(odd) {
    grid-column: 1 / -1;
  }
}

@media (max-width: 380px) {
  .home__quick {
    grid-template-columns: 1fr;
  }
}
</style>
