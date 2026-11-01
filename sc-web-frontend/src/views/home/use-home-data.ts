import { computed, onMounted, onUnmounted, ref } from 'vue'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { listDdlTasks } from '@/api/ddl'
import type { DdlTask, DdlTaskBoard } from '@/api/ddl'
import { listDocs } from '@/api/knowledge'
import type { KnowledgeDoc } from '@/api/knowledge'
import { listNotifications } from '@/api/notifications'
import type { NotificationItem } from '@/api/notifications'
import { listMessages } from '@/api/rag'
import type { ChatMessage } from '@/api/rag'
import { getUserConfig } from '@/api/user-config'
import { useAuthStore } from '@/stores/auth'
import { useCourseStore } from '@/stores/course'
import { useUiStore } from '@/stores/ui'
import {
  DEFAULT_TIMEZONE,
  errorMessage,
  formatDateLabel,
  greetingForHour,
  groupDdlCountByCourse,
  mergeNoticeItems,
  pickDefaultCourse,
  recentChatPairs,
  recentDocs,
  toDateString,
  todayTasks,
  zonedDateParts,
} from '@/views/home/dashboard-data'

/**
 * 首页数据聚合（P2）。
 *
 * 所有首页模块的取数都收敛在这里，后续后端补接口时只改本文件与 `src/api/*`，
 * 组件不需要重写。每个模块独立 loading/error/empty，`Promise.allSettled` 保证
 * 单个接口失败不会让整页空白。所有模块都接真实接口，未登录时不发请求，
 * 由组件按 `authenticated` 渲染「登录后查看」，不伪造任何数据。
 */

export type ModuleStatus = 'loading' | 'ready' | 'empty' | 'error'

export interface ModuleState<T> {
  status: ModuleStatus
  data: T
  error: string
}

const EMPTY_BOARD: DdlTaskBoard = {
  pending_inbox_tasks: [],
  pending_change_drafts: [],
  active_tasks: [],
  completed_tasks: [],
}

const MINUTE_MS = 60_000

export function useHomeData() {
  const auth = useAuthStore()
  const courseStore = useCourseStore()
  const ui = useUiStore()

  const coursesState = ref<ModuleState<CourseNamespace[]>>({
    status: 'loading',
    data: [],
    error: '',
  })
  const boardState = ref<ModuleState<DdlTaskBoard>>({
    status: 'loading',
    data: EMPTY_BOARD,
    error: '',
  })
  const notificationsState = ref<ModuleState<NotificationItem[]>>({
    status: 'loading',
    data: [],
    error: '',
  })
  const docsState = ref<ModuleState<KnowledgeDoc[]>>({
    status: 'loading',
    data: [],
    error: '',
  })
  const messagesState = ref<ModuleState<ChatMessage[]>>({
    status: 'loading',
    data: [],
    error: '',
  })
  const selectedCourse = ref<CourseNamespace | null>(null)
  const timezone = ref(DEFAULT_TIMEZONE)
  const unreadCount = ref(0)
  const nowMs = ref(Date.now())

  let clockTimer: ReturnType<typeof setInterval> | null = null

  const todayTasksState = computed<ModuleState<DdlTask[]>>(() => {
    const board = boardState.value
    if (board.status === 'loading') {
      return { status: 'loading', data: [], error: '' }
    }
    if (board.status === 'error') {
      return { status: 'error', data: [], error: board.error }
    }
    const items = todayTasks(board.data, 5)
    return {
      status: items.length === 0 ? 'empty' : 'ready',
      data: items,
      error: '',
    }
  })

  const noticesState = computed<ModuleState<NotificationItem[]>>(() => {
    const state = notificationsState.value
    if (state.status === 'ready' && state.data.length === 0) {
      return { ...state, status: 'empty' }
    }
    return state
  })

  const recentDocsState = computed<ModuleState<KnowledgeDoc[]>>(() => {
    const state = docsState.value
    if (state.status !== 'ready') {
      return state
    }
    const items = recentDocs(state.data, 5)
    return { status: items.length === 0 ? 'empty' : 'ready', data: items, error: '' }
  })

  const recentChatsState = computed<ModuleState<ChatMessage[]>>(() => {
    const state = messagesState.value
    if (state.status !== 'ready') {
      return state
    }
    return {
      status: recentChatPairs(state.data, 4).length === 0 ? 'empty' : 'ready',
      data: state.data,
      error: '',
    }
  })

  const chatPairs = computed(() => recentChatPairs(messagesState.value.data, 4))
  const courseDdlCounts = computed<Record<string, number> | null>(() =>
    boardState.value.status === 'ready'
      ? groupDdlCountByCourse(boardState.value.data)
      : null,
  )
  const currentCourse = computed(() => selectedCourse.value)

  const dateParts = computed(() => zonedDateParts(new Date(nowMs.value), timezone.value))
  const greeting = computed(() => greetingForHour(dateParts.value.hour))
  const dateLabel = computed(() => formatDateLabel(dateParts.value))
  const todayIso = computed(() =>
    toDateString(dateParts.value.year, dateParts.value.month, dateParts.value.day),
  )

  async function loadTimezone(): Promise<void> {
    if (!auth.isAuthenticated) {
      return
    }
    try {
      const config = await getUserConfig()
      timezone.value = config.timezone === '' ? DEFAULT_TIMEZONE : config.timezone
    } catch {
      timezone.value = DEFAULT_TIMEZONE
    }
  }

  async function loadCourseContent(courseId: string | null): Promise<void> {
    if (courseId === null) {
      docsState.value = { status: 'empty', data: [], error: '' }
      messagesState.value = { status: 'empty', data: [], error: '' }
      return
    }
    docsState.value = { status: 'loading', data: [], error: '' }
    messagesState.value = { status: 'loading', data: [], error: '' }

    const [docsResult, messagesResult] = await Promise.allSettled([
      listDocs(courseId),
      listMessages(courseId, null, 20),
    ])

    if (docsResult.status === 'fulfilled') {
      docsState.value = { status: 'ready', data: docsResult.value, error: '' }
    } else {
      docsState.value = {
        status: 'error',
        data: [],
        error: errorMessage(docsResult.reason, '最近学习读取失败。'),
      }
    }

    if (messagesResult.status === 'fulfilled') {
      messagesState.value = {
        status: 'ready',
        data: messagesResult.value.items,
        error: '',
      }
    } else {
      messagesState.value = {
        status: 'error',
        data: [],
        error: errorMessage(messagesResult.reason, '最近问答读取失败。'),
      }
    }
  }

  async function loadCourses(): Promise<void> {
    if (!auth.isAuthenticated) {
      coursesState.value = { status: 'empty', data: [], error: '' }
      await loadCourseContent(null)
      return
    }
    coursesState.value = { status: 'loading', data: [], error: '' }
    try {
      const courses = await listCourses()
      coursesState.value = {
        status: courses.length === 0 ? 'empty' : 'ready',
        data: courses,
        error: '',
      }
      const course = pickDefaultCourse(courses, courseStore.selectedCourseId)
      selectedCourse.value = course
      if (course !== null) {
        courseStore.select(course.course_id)
      }
      await loadCourseContent(course?.course_id ?? null)
    } catch (error) {
      const message = errorMessage(error, '课程列表读取失败。')
      coursesState.value = { status: 'error', data: [], error: message }
      selectedCourse.value = null
      docsState.value = { status: 'error', data: [], error: message }
      messagesState.value = { status: 'error', data: [], error: message }
    }
  }

  async function loadBoard(): Promise<void> {
    if (!auth.isAuthenticated) {
      boardState.value = { status: 'empty', data: EMPTY_BOARD, error: '' }
      return
    }
    boardState.value = { status: 'loading', data: EMPTY_BOARD, error: '' }
    try {
      const board = await listDdlTasks({ statusGroup: 'ALL' })
      boardState.value = { status: 'ready', data: board, error: '' }
    } catch (error) {
      boardState.value = {
        status: 'error',
        data: EMPTY_BOARD,
        error: errorMessage(error, 'DDL 看板读取失败。'),
      }
    }
  }

  async function loadNotifications(): Promise<void> {
    if (!auth.isAuthenticated) {
      notificationsState.value = { status: 'empty', data: [], error: '' }
      unreadCount.value = 0
      ui.setUnreadNotificationCount(0)
      return
    }
    notificationsState.value = { status: 'loading', data: [], error: '' }
    const [allResult, unreadResult] = await Promise.allSettled([
      listNotifications({ size: 4 }),
      listNotifications({ readStatus: 'UNREAD', size: 4 }),
    ])
    const all =
      allResult.status === 'fulfilled' ? allResult.value : null
    const unread =
      unreadResult.status === 'fulfilled' ? unreadResult.value : null
    if (all === null && unread === null) {
      const firstError: unknown =
        allResult.status === 'rejected'
          ? allResult.reason
          : unreadResult.status === 'rejected'
            ? unreadResult.reason
            : undefined
      notificationsState.value = {
        status: 'error',
        data: [],
        error: errorMessage(firstError, '通知读取失败。'),
      }
      return
    }
    const items = mergeNoticeItems(all?.items ?? [], unread?.items ?? [], 4)
    const totalUnread = all?.unread_count ?? unread?.unread_count ?? 0
    unreadCount.value = totalUnread
    ui.setUnreadNotificationCount(totalUnread)
    notificationsState.value = {
      status: items.length === 0 ? 'empty' : 'ready',
      data: items,
      error: '',
    }
  }

  async function refreshAll(): Promise<void> {
    await Promise.all([
      loadTimezone(),
      loadCourses(),
      loadBoard(),
      loadNotifications(),
    ])
  }

  async function retryContent(): Promise<void> {
    await loadCourseContent(currentCourse.value?.course_id ?? null)
  }

  onMounted(() => {
    void refreshAll()
    clockTimer = setInterval(() => {
      nowMs.value = Date.now()
    }, MINUTE_MS)
  })

  onUnmounted(() => {
    if (clockTimer !== null) {
      clearInterval(clockTimer)
      clockTimer = null
    }
  })

  return {
    coursesState,
    boardState,
    todayTasksState,
    noticesState,
    recentDocsState,
    recentChatsState,
    courseDdlCounts,
    selectedCourse: currentCourse,
    chatPairs,
    timezone,
    greeting,
    dateLabel,
    todayIso,
    nowMs,
    unreadCount,
    refreshAll,
    retryCourses: loadCourses,
    retryBoard: loadBoard,
    retryNotifications: loadNotifications,
    retryContent,
  }
}
