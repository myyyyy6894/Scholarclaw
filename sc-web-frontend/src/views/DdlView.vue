<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import { CalendarDays, ClipboardPaste, List, Plus, RefreshCw } from '@lucide/vue'
import { ElMessageBox } from 'element-plus'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import {
  createDdlTask,
  decideDdlDraft,
  deleteDdlTask,
  extractDdlTask,
  listDdlTasks,
  setDdlTaskCompletion,
  updateDdlTask,
} from '@/api/ddl'
import type { DdlTask, DraftDecision, TaskChangeDraft } from '@/api/ddl'
import { ApiError } from '@/api/error'
import DdlCalendar from '@/components/home/DdlCalendar.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatCard from '@/components/ui/StatCard.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import {
  DEFAULT_TIMEZONE,
  toDateString,
  zonedDateParts,
} from '@/views/home/dashboard-data'
import DdlTaskForm from '@/views/ddl/DdlTaskForm.vue'
import {
  boardCounts,
  buildCreatePayload,
  buildUpdatePayload,
  countdown,
  deadlineText,
  draftDiffRows,
  draftExpiryText,
  draftNewDeadline,
  editExtractInput,
  emptyBoard,
  emptyTaskForm,
  findTask,
  forceCreateOperation,
  formatTimestamp,
  inferredNotice,
  isStaleExtractResponse,
  manualCreateSubmitState,
  markExtractSubmitted,
  newClientRequestId,
  newExtractOperation,
  newManualCreateOperation,
  originalTimeInfo,
  pendingCardInfo,
  precisionLabel,
  rawTextError,
  resetManualCreateAfterConflict,
  statusLabel,
  statusTagType,
  taskFormError,
  taskFormFrom,
  type Countdown,
  type DdlTaskFormState,
  type ExtractOperationState,
  type ManualCreateOperationState,
  type OriginalTimeInfo,
  type PendingCardInfo,
} from '@/views/ddl/ddl-task'

/**
 * DDL 雷达工作台（`06` TASK-705、`04` §4.6）。
 *
 * 覆盖通知粘贴提取、手动新建、待确认收件箱、重复通知变更决策、编辑 / 确认激活、
 * 完成勾选与删除任务。页面只展示后端真实状态：
 *
 * * 模糊或缺失时间不会被前端补成具体时刻；
 * * 变更草案必须由用户显式选择“确认更新 / 仍然新建 / 忽略”，前端不静默合并；
 * * 每次请求的 `client_request_id` 在重试之间保持不变，只有“仍然新建”才换新值。
 */

const board = ref(emptyBoard())
const courses = ref<CourseNamespace[]>([])
const courseFilter = ref('')
const loading = ref(true)
const loadError = ref('')
const busyTaskId = ref<string | null>(null)
const nowMs = ref(Date.now())
let clockTimer: number | null = null

const counts = computed(() => boardCounts(board.value))
const countdowns = computed<Record<string, Countdown | null>>(() => {
  const result: Record<string, Countdown | null> = {}
  for (const task of board.value.active_tasks) {
    result[task.task_id] = countdown(task, nowMs.value)
  }
  return result
})
const pendingInfos = computed<Record<string, PendingCardInfo>>(() => {
  const result: Record<string, PendingCardInfo> = {}
  for (const task of board.value.pending_inbox_tasks) {
    result[task.task_id] = pendingCardInfo(task)
  }
  return result
})

type DdlViewMode = 'list' | 'calendar'
type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

const viewMode = ref<DdlViewMode>('list')
const calendarDate = ref<string | null>(null)

const timezone = computed(() => {
  const task =
    board.value.active_tasks[0] ??
    board.value.pending_inbox_tasks[0] ??
    board.value.completed_tasks[0]
  return task?.user_timezone ?? DEFAULT_TIMEZONE
})
const todayIso = computed(() => {
  const parts = zonedDateParts(new Date(nowMs.value), timezone.value)
  return toDateString(parts.year, parts.month, parts.day)
})
const calendarTasks = computed<DdlTask[]>(() => [
  ...board.value.pending_inbox_tasks,
  ...board.value.active_tasks,
  ...board.value.completed_tasks,
])
const dateTasks = computed<DdlTask[]>(() => {
  if (calendarDate.value === null) {
    return []
  }
  return calendarTasks.value
    .filter((task) => task.due_date === calendarDate.value)
    .sort((left, right) =>
      (left.due_time ?? '99:99:99').localeCompare(right.due_time ?? '99:99:99'),
    )
})

function switchView(mode: DdlViewMode): void {
  viewMode.value = mode
  if (mode === 'calendar' && calendarDate.value === null) {
    calendarDate.value = todayIso.value
  }
}

function onCalendarSelect(date: string): void {
  calendarDate.value = date
}

function taskTime(task: DdlTask): string {
  return task.due_time === null ? '全天' : task.due_time.slice(0, 5)
}

function statusTone(status: DdlTask['status']): Tone {
  const tone = statusTagType(status)
  return tone === 'info' ? 'neutral' : tone
}

onMounted(() => {
  void loadCourses()
  void loadBoard()
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onUnmounted(() => {
  if (clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
})

async function loadCourses(): Promise<void> {
  try {
    courses.value = await listCourses()
  } catch (error) {
    // 课程列表失败不阻断看板：任务里自带 course_name 文本，仍可完整管理
    notifyError(error)
  }
}

async function loadBoard(): Promise<void> {  loadError.value = ''
  try {
    board.value = await listDdlTasks({
      statusGroup: 'ALL',
      courseId: courseFilter.value === '' ? null : courseFilter.value,
    })
  } catch (error) {
    loadError.value =
      error instanceof ApiError ? error.message : 'DDL 看板加载失败，请稍后重试。'
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await loadBoard()
}

/* ------------------------------ 通知文本提取 ------------------------------ */

const extractVisible = ref(false)
const extractText = ref('')
const extractCourseId = ref('')
const extractOperation = ref<ExtractOperationState>(
  newExtractOperation('', '', newClientRequestId()),
)
const extractBusy = ref(false)
const semanticHint = ref<DdlTask | null>(null)

function openExtractDialog(): void {
  extractText.value = ''
  extractCourseId.value = courseFilter.value
  extractOperation.value = newExtractOperation(
    '',
    courseFilter.value,
    newClientRequestId(),
  )
  semanticHint.value = null
  extractVisible.value = true
}

// 已提交后改动原文/课程 → 新的操作身份（新 requestId、forceCreate 归零）；
// 仅切换输入不会悄悄复用旧请求 ID，也不会保留旧的“仍然新建”状态。
watch([extractText, extractCourseId], () => {
  const next = editExtractInput(
    extractOperation.value,
    extractText.value,
    extractCourseId.value,
    newClientRequestId(),
  )
  if (next !== extractOperation.value) {
    extractOperation.value = next
  }
  semanticHint.value = null
})

async function submitExtract(forceCreate: boolean): Promise<void> {
  const error = rawTextError(extractText.value)
  if (error !== null) {
    notifyWarning(error)
    return
  }
  let operation = extractOperation.value
  if (forceCreate) {
    // “仍然新建”是全新操作：仅在尚未处于 force 状态时换新幂等 ID；
    // 失败重试复用这个新 ID，直到输入变化或成功。
    operation = forceCreateOperation(operation, newClientRequestId('req_ddl_force'))
    semanticHint.value = null
  }
  operation = markExtractSubmitted(operation)
  extractOperation.value = operation
  const submittedRequestId = operation.requestId
  extractBusy.value = true
  try {
    const result = await extractDdlTask({
      raw_text: operation.rawText,
      client_request_id: operation.requestId,
      force_create: operation.forceCreate,
      course_id: operation.courseId === '' ? null : operation.courseId,
    })
    // 慢响应期间用户已改变输入（操作身份变化）→ 丢弃旧响应，避免把旧结果套到新输入上。
    if (isStaleExtractResponse(extractOperation.value, submittedRequestId)) {
      return
    }

    if (result.extract_outcome === 'SEMANTIC_DUPLICATE_HINT') {
      semanticHint.value = result.task
      notifyWarning(`近 14 天已存在相同通知创建的任务 ${result.task.display_code}。`)
      await refreshAll()
      return
    }

    if (result.extract_outcome === 'CHANGE_DRAFT_CONFIRM_REQUIRED') {
      extractVisible.value = false
      if (result.change_draft === null) {
        notifyWarning('后端未返回变更草案，请刷新看板后重试。')
        await refreshAll()
        return
      }
      openDraftDialog(result.task, result.change_draft)
      await refreshAll()
      return
    }

    extractVisible.value = false
    notifySuccess(
      result.extract_outcome === 'IDEMPOTENT_HIT'
        ? `该请求已处理过，返回既有任务 ${result.task.display_code}（${statusLabel(result.task.status)}）。`
        : `已创建任务 ${result.task.display_code}（${statusLabel(result.task.status)}）。`,
    )
    await refreshAll()
  } catch (err) {
    // 失败时保留原 client_request_id：用户重试同一请求仍是同一个幂等操作
    notifyError(err)
  } finally {
    extractBusy.value = false
  }
}

/* ------------------------------ 变更草案决策 ------------------------------ */

const draftVisible = ref(false)
const draftTask = ref<DdlTask | null>(null)
const draftPayload = ref<TaskChangeDraft | null>(null)
const draftTargetId = ref('')
const draftRequestId = ref('')
const draftBusy = ref(false)

function openDraftDialog(task: DdlTask | null, draft: TaskChangeDraft): void {
  draftTask.value = task ?? findTask(board.value, draft.target_task_id)
  draftPayload.value = draft
  draftTargetId.value = draft.target_task_id
  draftRequestId.value = newClientRequestId('req_ddl_draft')
  draftVisible.value = true
}

function openDraftFromBoard(draft: TaskChangeDraft): void {
  openDraftDialog(findTask(board.value, draft.target_task_id), draft)
}

/** 草案截止时间按草案自带的用户时区快照展示，不依赖目标任务是否在当前筛选结果中。 */
function draftDeadline(draft: TaskChangeDraft): string {
  return draftNewDeadline(draft)
}

async function decideDraft(decision: DraftDecision): Promise<void> {
  if (draftPayload.value === null) {
    return
  }
  draftBusy.value = true
  try {
    const task = await decideDdlDraft(draftTargetId.value, {
      decision,
      client_request_id: draftRequestId.value,
    })
    draftVisible.value = false
    draftPayload.value = null
    if (decision === 'CONFIRM_UPDATE') {
      notifySuccess(`已用新通知更新任务 ${task.display_code}（${statusLabel(task.status)}）。`)
    } else if (decision === 'CREATE_NEW') {
      notifySuccess(`已保留原任务并另存为新任务 ${task.display_code}。`)
    } else {
      notifySuccess(`已忽略任务 ${task.display_code} 的变更草案，原任务保持不变。`)
    }
    await refreshAll()
  } catch (error) {
    notifyError(error)
  } finally {
    draftBusy.value = false
  }
}

/* ------------------------------ 手动新建 / 编辑 ------------------------------ */

const createVisible = ref(false)
const createForm = ref<DdlTaskFormState>(emptyTaskForm())
const createOperation = ref<ManualCreateOperationState>(
  newManualCreateOperation(newClientRequestId('req_ddl_manual')),
)
const createBusy = ref(false)

function openCreateDialog(): void {
  createForm.value = emptyTaskForm()
  // 每次新建都是独立操作；同一操作内的失败重试由 manualCreateSubmitState 复用 ID。
  createOperation.value = newManualCreateOperation(newClientRequestId('req_ddl_manual'))
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const error = taskFormError(createForm.value)
  if (error !== null) {
    notifyWarning(error)
    return
  }
  // 未修改表单的重试复用原请求 ID；已提交后修改表单则换新 ID，避免 409 IDEMPOTENCY_CONFLICT。
  const prepared = manualCreateSubmitState(
    createOperation.value,
    createForm.value,
    newClientRequestId('req_ddl_manual'),
  )
  createOperation.value = prepared.operation
  createBusy.value = true
  try {
    const task = await createDdlTask(buildCreatePayload(createForm.value, prepared.requestId))
    createVisible.value = false
    notifySuccess(`已创建任务 ${task.display_code}（${statusLabel(task.status)}）。`)
    await refreshAll()
  } catch (err) {
    if (err instanceof ApiError && err.code === 'IDEMPOTENCY_CONFLICT') {
      // 原 ID 已被其它内容占用：明确告知这是新操作，并生成新 ID 供用户重新提交。
      createOperation.value = resetManualCreateAfterConflict(
        createOperation.value,
        newClientRequestId('req_ddl_manual'),
      )
      notifyWarning(
        '该请求编号已被其他内容占用（IDEMPOTENCY_CONFLICT）。已生成新的操作编号，请确认表单后重新提交。',
      )
    } else {
      notifyError(err)
    }
  } finally {
    createBusy.value = false
  }
}

const editVisible = ref(false)
const editForm = ref<DdlTaskFormState>(emptyTaskForm())
const editTask = ref<DdlTask | null>(null)
const editBusy = ref(false)

const editOriginalTime = computed<OriginalTimeInfo | null>(() =>
  editTask.value === null ? null : originalTimeInfo(editTask.value),
)

const editHint = computed(() => {
  const task = editTask.value
  if (task === null) {
    return ''
  }
  if (task.status === 'PENDING_TIME' || task.status === 'PENDING_CONFIRM') {
    return '补充明确的截止日期后保存，任务会激活并进入 T-24h / T-6h / 逾期提醒范围。'
  }
  if (task.status === 'COMPLETED') {
    return '已完成任务只更新字段，仍保持完成状态；如需重新计时，请先取消完成勾选。'
  }
  if (task.status === 'OVERDUE') {
    return '将截止时间改到未来即可恢复为进行中；修改会按新剩余时长重置提醒位。'
  }
  return ''
})

function openEditDialog(task: DdlTask): void {
  editTask.value = task
  editForm.value = taskFormFrom(task)
  editVisible.value = true
}

async function submitEdit(reconfirmTime = false): Promise<void> {
  const task = editTask.value
  if (task === null) {
    return
  }
  const error = taskFormError(editForm.value)
  if (error !== null) {
    notifyWarning(error)
    return
  }
  editBusy.value = true
  try {
    const updated = await updateDdlTask(
      task.task_id,
      buildUpdatePayload(editForm.value, reconfirmTime),
    )
    editVisible.value = false
    notifySuccess(`任务 ${updated.display_code} 已保存（${statusLabel(updated.status)}）。`)
    await refreshAll()
  } catch (err) {
    notifyError(err)
  } finally {
    editBusy.value = false
  }
}

/* ------------------------------ 完成 / 取消 ------------------------------ */

async function toggleCompletion(task: DdlTask, completed: boolean): Promise<void> {
  busyTaskId.value = task.task_id
  try {
    const updated = await setDdlTaskCompletion(task.task_id, completed)
    notifySuccess(
      completed
        ? `任务 ${updated.display_code} 已标记完成。`
        : `任务 ${updated.display_code} 已恢复为${statusLabel(updated.status)}。`,
    )
    await refreshAll()
  } catch (error) {
    notifyError(error)
  } finally {
    busyTaskId.value = null
  }
}

async function cancelTask(task: DdlTask): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除任务《${task.task_title}》？任务会从看板移除（置为 CANCELED），对应的变更草案与后续提醒一并清空。`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  busyTaskId.value = task.task_id
  try {
    await deleteDdlTask(task.task_id)
    notifySuccess(`任务 ${task.display_code} 已删除。`)
    await refreshAll()
  } catch (error) {
    notifyError(error)
  } finally {
    busyTaskId.value = null
  }
}
</script>

<template>
  <div class="ddl-page">
    <section class="ddl-hero">
      <div class="ddl-hero__copy">
        <span class="ddl-hero__eyebrow">DDL RADAR</span>
        <h2>DDL 任务</h2>
        <p>
          粘贴群通知即可提取结构化待办；模糊时间会进入待确认收件箱，重复通知的截止时间变更必须先确认，
          系统不会静默覆盖已有任务。
        </p>
        <div class="ddl-hero__meta">
          <TagBadge label="提醒调度已启用" tone="success" dot />
          <span>站内通知始终可用；QQ 推送失败或离线时降级为仅 Web 展示。</span>
        </div>
      </div>
      <div class="ddl-hero__side">
        <div class="ddl-hero__art">
          <img src="/illustrations/feature-ddl.png" alt="" />
        </div>
        <div class="ddl-hero__actions">
          <el-button :icon="Plus" @click="openCreateDialog">手动新建</el-button>
          <el-button type="primary" :icon="ClipboardPaste" @click="openExtractDialog">
            粘贴通知提取
          </el-button>
        </div>
      </div>
    </section>

    <div class="ddl-metrics">
      <StatCard label="待确认" :value="counts.pending" tone="warning" hint="待补充时间" />
      <StatCard label="变更草案" :value="counts.drafts" tone="primary" hint="需确认后生效" />
      <StatCard label="进行中" :value="counts.active" tone="default" hint="按截止时间排序" />
      <StatCard label="已逾期" :value="counts.overdue" tone="danger" hint="需要尽快处理" />
      <StatCard label="已完成" :value="counts.completed" tone="success" hint="最近完成" />
    </div>

    <BaseCard padding="sm">
      <div class="ddl-toolbar">
        <div class="ddl-toolbar__filters">
          <el-select
            v-model="courseFilter"
            clearable
            filterable
            placeholder="全部课程"
            class="ddl-toolbar__select"
            @change="loadBoard"
          >
            <el-option
              v-for="course in courses"
              :key="course.course_id"
              :label="course.course_name"
              :value="course.course_id"
            />
          </el-select>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadBoard">刷新</el-button>
        </div>

        <div class="ddl-view-toggle" role="group" aria-label="视图切换">
          <button
            type="button"
            :class="{ 'is-active': viewMode === 'list' }"
            @click="switchView('list')"
          >
            <List :size="15" :stroke-width="1.8" /> 列表
          </button>
          <button
            type="button"
            :class="{ 'is-active': viewMode === 'calendar' }"
            @click="switchView('calendar')"
          >
            <CalendarDays :size="15" :stroke-width="1.8" /> 日历
          </button>
        </div>
      </div>
    </BaseCard>

    <el-alert
      v-if="loadError !== ''"
      type="error"
      :closable="false"
      show-icon
      title="DDL 看板加载失败"
      :description="loadError"
    />

    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="viewMode === 'list'">
      <BaseCard
        v-if="board.pending_change_drafts.length > 0"
        :title="`重复通知变更草案（${board.pending_change_drafts.length}）`"
        subtitle="变更必须确认后才生效"
      >
        <template #actions>
          <TagBadge label="需确认后才生效" tone="warning" />
        </template>

        <article
          v-for="draft in board.pending_change_drafts"
          :key="draft.target_task_id"
          class="ddl-draft"
        >
          <div>
            <div class="ddl-draft__title">
              任务 #T{{ draft.target_task_id }} 收到新的截止时间或交付要求
            </div>
            <div class="ddl-muted">
              新截止：{{ draftDeadline(draft) }}
              <span v-if="draftExpiryText(draft) !== null"> · {{ draftExpiryText(draft) }}</span>
            </div>
            <div v-if="draft.raw_time_text !== null" class="ddl-muted">
              原文时间：{{ draft.raw_time_text }}
            </div>
          </div>
          <el-button type="primary" size="small" @click="openDraftFromBoard(draft)">
            查看对比并决策
          </el-button>
        </article>
      </BaseCard>

      <BaseCard
        :title="`待确认收件箱（${board.pending_inbox_tasks.length}）`"
        subtitle="补充时间后进入 T-24h / T-6h / 逾期提醒范围"
      >
        <EmptyState
          v-if="board.pending_inbox_tasks.length === 0"
          image="/illustrations/status-schedule.png"
          title="没有待确认的任务"
          description="模糊时间或缺失时间的通知会先进入这里。"
          compact
        />
        <div v-else class="ddl-inbox">
          <article
            v-for="task in board.pending_inbox_tasks"
            :key="task.task_id"
            class="ddl-inbox__card"
          >
            <header class="ddl-inbox__head">
              <div>
                <span class="ddl-code">{{ task.display_code }}</span>
                <strong>{{ task.task_title }}</strong>
              </div>
              <TagBadge :label="statusLabel(task.status)" :tone="statusTone(task.status)" dot />
            </header>
            <div class="ddl-muted">
              {{ task.course_name }} · 时间精度：{{ precisionLabel(task.time_precision) }}
            </div>
            <div class="ddl-inbox__time">
              <div>解析截止：{{ pendingInfos[task.task_id]?.deadline ?? '未生成截止时间' }}</div>
              <div v-if="pendingInfos[task.task_id]?.rawTime !== null">
                原文时间：{{ pendingInfos[task.task_id]?.rawTime }}
              </div>
              <div v-if="pendingInfos[task.task_id]?.timeInferred">
                原通知未注明具体时刻，暂按当日 23:59 统计
              </div>
            </div>
            <p class="ddl-inbox__reason">{{ pendingInfos[task.task_id]?.reason }}</p>
            <div class="ddl-inbox__actions">
              <el-button type="primary" size="small" @click="openEditDialog(task)">
                补充时间 / 编辑
              </el-button>
              <el-button
                size="small"
                :disabled="busyTaskId === task.task_id"
                @click="cancelTask(task)"
              >
                删除任务
              </el-button>
            </div>
          </article>
        </div>
      </BaseCard>

      <BaseCard
        :title="`进行中与已逾期（${board.active_tasks.length}）`"
        subtitle="按截止时间升序"
      >
        <EmptyState
          v-if="board.active_tasks.length === 0"
          image="/illustrations/achievement-trophy.png"
          title="没有进行中的任务"
          description="所有 DDL 都已完成，或还没有已确认截止时间的任务。"
          compact
        />
        <el-table v-else :data="board.active_tasks" row-key="task_id" size="small">
          <el-table-column label="完成" width="60">
            <template #default="{ row }">
              <el-checkbox
                :model-value="row.status === 'COMPLETED'"
                :disabled="busyTaskId === row.task_id"
                @change="(value: string | number | boolean) => toggleCompletion(row, value === true)"
              />
            </template>
          </el-table-column>
          <el-table-column label="任务" min-width="220">
            <template #default="{ row }">
              <div>
                <span class="ddl-code">{{ row.display_code }}</span>
                <strong>{{ row.task_title }}</strong>
              </div>
              <div class="ddl-muted">{{ row.course_name }}</div>
            </template>
          </el-table-column>
          <el-table-column label="截止时间" min-width="240">
            <template #default="{ row }">
              <div>{{ deadlineText(row) }}</div>
              <div class="ddl-muted">
                {{ precisionLabel(row.time_precision) }}
                <span v-if="row.raw_time_text !== null"> · 原文“{{ row.raw_time_text }}”</span>
              </div>
              <TagBadge
                v-if="inferredNotice(row) !== null"
                label="未注明具体时刻，按当日 23:59 统计"
                tone="neutral"
              />
              <div
                v-if="countdowns[row.task_id] !== undefined && countdowns[row.task_id] !== null"
                class="ddl-countdown"
                :class="{ 'ddl-countdown--overdue': countdowns[row.task_id]?.overdue }"
              >
                {{ countdowns[row.task_id]?.text }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <TagBadge :label="statusLabel(row.status)" :tone="statusTone(row.status)" dot />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="right" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditDialog(row)">
                编辑
              </el-button>
              <el-button
                text
                type="danger"
                size="small"
                :disabled="busyTaskId === row.task_id"
                @click="cancelTask(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </BaseCard>

      <BaseCard :title="`已完成（最近 ${board.completed_tasks.length} 条）`">
        <el-empty
          v-if="board.completed_tasks.length === 0"
          description="还没有已完成的任务。"
          :image-size="72"
        />
        <el-table v-else :data="board.completed_tasks" row-key="task_id" size="small">
          <el-table-column label="完成" width="60">
            <template #default="{ row }">
              <el-checkbox
                :model-value="true"
                :disabled="busyTaskId === row.task_id"
                @change="(value: string | number | boolean) => toggleCompletion(row, value === true)"
              />
            </template>
          </el-table-column>
          <el-table-column label="任务" min-width="220">
            <template #default="{ row }">
              <div>
                <span class="ddl-code">{{ row.display_code }}</span>
                <strong>{{ row.task_title }}</strong>
              </div>
              <div class="ddl-muted">{{ row.course_name }}</div>
            </template>
          </el-table-column>
          <el-table-column label="截止时间" min-width="200">
            <template #default="{ row }">{{ deadlineText(row) }}</template>
          </el-table-column>
          <el-table-column label="完成时间" width="180">
            <template #default="{ row }">
              {{ formatTimestamp(row.completed_at, row.user_timezone) ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="right" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditDialog(row)">
                编辑
              </el-button>
              <el-button
                text
                type="danger"
                size="small"
                :disabled="busyTaskId === row.task_id"
                @click="cancelTask(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </BaseCard>
    </template>

    <template v-else>
      <DdlCalendar
        :tasks="calendarTasks"
        :today-iso="todayIso"
        :show-day-list="false"
        @select="onCalendarSelect"
      />

      <BaseCard
        :title="calendarDate === null ? '当天任务' : `${calendarDate} 的任务`"
        subtitle="点击日历中的日期查看当天全部任务"
      >
        <EmptyState
          v-if="dateTasks.length === 0"
          image="/illustrations/achievement-trophy.png"
          title="当天没有 DDL 任务"
          description="换一天看看，或回到列表视图管理全部任务。"
          compact
        />
        <ul v-else class="ddl-day-list">
          <li v-for="task in dateTasks" :key="task.task_id" class="ddl-day-task">
            <span class="ddl-day-task__time">{{ taskTime(task) }}</span>
            <span class="ddl-day-task__main">
              <strong>
                <span class="ddl-code">{{ task.display_code }}</span>{{ task.task_title }}
              </strong>
              <small>{{ task.course_name }} · {{ precisionLabel(task.time_precision) }}</small>
            </span>
            <TagBadge :label="statusLabel(task.status)" :tone="statusTone(task.status)" dot />
            <el-button text type="primary" size="small" @click="openEditDialog(task)">
              编辑
            </el-button>
          </li>
        </ul>
      </BaseCard>
    </template>

    <el-dialog
      v-model="extractVisible"
      title="粘贴通知提取 DDL"
      width="680px"
      :show-close="!extractBusy"
      :close-on-click-modal="!extractBusy"
      :close-on-press-escape="!extractBusy"
    >
      <el-form label-position="top">
        <el-form-item label="通知原文" required>
          <el-input
            v-model="extractText"
            type="textarea"
            :rows="8"
            maxlength="4000"
            show-word-limit
            :disabled="extractBusy"
            placeholder="粘贴群通知原文，例如：各位同学，本周五（10月16日）下午5点前请将实验报告与源码以 学号_姓名.zip 发至学习通作业区。"
          />
        </el-form-item>
        <el-form-item label="关联课程（可选）">
          <el-select
            v-model="extractCourseId"
            clearable
            filterable
            placeholder="不指定时由后端按提取到的课程名匹配已有课程"
            class="sc-ddl-form__control"
            :disabled="extractBusy"
          >
            <el-option
              v-for="course in courses"
              :key="course.course_id"
              :label="course.course_name"
              :value="course.course_id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="semanticHint !== null"
        type="warning"
        :closable="false"
        show-icon
        :title="`近 14 天已存在相同通知创建的任务 ${semanticHint.display_code}`"
        :description="`《${semanticHint.task_title}》，截止 ${deadlineText(semanticHint)}。如确属不同事项，点击“仍然新建”会使用新的请求幂等 ID 创建独立任务。`"
      />

      <template #footer>
        <el-button :disabled="extractBusy" @click="extractVisible = false">关闭</el-button>
        <el-button
          v-if="semanticHint !== null || extractOperation.forceCreate"
          type="primary"
          :loading="extractBusy"
          @click="submitExtract(true)"
        >
          {{ semanticHint !== null ? '仍然新建' : '仍然新建（重试）' }}
        </el-button>
        <el-button v-else type="primary" :loading="extractBusy" @click="submitExtract(false)">
          开始提取
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="draftVisible"
      title="重复通知变更确认"
      width="760px"
      :close-on-click-modal="!draftBusy"
      :close-on-press-escape="!draftBusy"
    >
      <p class="sc-ddl-dialog__intro">
        检测到同课程相似任务的截止时间或交付要求发生变化。系统不会静默覆盖原任务，请选择处理方式。
      </p>

      <template v-if="draftPayload !== null">
        <div class="sc-ddl-dialog__target">
          原任务：
          <span class="sc-ddl-code">
            {{ draftTask?.display_code ?? `#T${draftPayload.target_task_id}` }}
          </span>
          <span v-if="draftTask !== null">
            《{{ draftTask.task_title }}》 · 当前截止 {{ deadlineText(draftTask) }}
          </span>
        </div>

        <el-table :data="draftDiffRows(draftTask, draftPayload)" size="small" border>
          <el-table-column prop="label" label="字段" width="110" />
          <el-table-column label="原任务" min-width="170">
            <template #default="{ row }">
              <span :class="{ 'sc-ddl-diff__changed': row.changed }">{{ row.before }}</span>
            </template>
          </el-table-column>
          <el-table-column label="新通知解析结果" min-width="170">
            <template #default="{ row }">
              <span :class="{ 'sc-ddl-diff__changed': row.changed }">{{ row.after }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="sc-ddl-dialog__raw">
          <div v-if="draftPayload.raw_time_text !== null" class="sc-ddl-muted">
            原文时间：{{ draftPayload.raw_time_text }}
          </div>
          <div v-if="draftPayload.raw_source_text !== null" class="sc-ddl-muted">
            原文：{{ draftPayload.raw_source_text }}
          </div>
          <div v-if="draftExpiryText(draftPayload) !== null" class="sc-ddl-muted">
            {{ draftExpiryText(draftPayload) }}
          </div>
        </div>

        <el-alert
          v-if="draftPayload.new_time_ambiguity_reason !== null"
          class="sc-ddl-dialog__notice"
          type="warning"
          :closable="false"
          show-icon
          :title="draftPayload.new_time_ambiguity_reason"
        />

        <el-alert
          v-if="draftPayload.new_time_inferred"
          type="info"
          :closable="false"
          show-icon
          title="新通知只给出日期，系统暂按当日 23:59 统计倒计时。"
        />
      </template>

      <template #footer>
        <el-button :disabled="draftBusy" @click="decideDraft('DISCARD')">忽略草案</el-button>
        <el-button :disabled="draftBusy" @click="decideDraft('CREATE_NEW')">仍然新建</el-button>
        <el-button type="primary" :loading="draftBusy" @click="decideDraft('CONFIRM_UPDATE')">
          确认更新原任务
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="createVisible"
      title="手动新建 DDL 任务"
      width="620px"
      :close-on-click-modal="!createBusy"
      :close-on-press-escape="!createBusy"
    >
      <DdlTaskForm v-model="createForm" :courses="courses" />
      <template #footer>
        <el-button :disabled="createBusy" @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createBusy" @click="submitCreate">创建任务</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="editVisible"
      :title="`编辑任务 ${editTask?.display_code ?? ''}`"
      width="620px"
      :close-on-click-modal="!editBusy"
      :close-on-press-escape="!editBusy"
    >
      <DdlTaskForm
        v-model="editForm"
        :courses="courses"
        :activate-hint="editHint"
        :original-time="editOriginalTime"
        @reconfirm="submitEdit(true)"
      />
      <template #footer>
        <el-button :disabled="editBusy" @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editBusy" @click="submitEdit(false)">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ddl-page {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
}

.ddl-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 28px;
  overflow: hidden;
  border: 1px solid var(--sc-border);
  border-radius: 16px;
  background: linear-gradient(
    120deg,
    var(--sc-primary-soft) 0%,
    var(--sc-surface) 58%,
    var(--sc-accent-soft) 100%
  );
  box-shadow: var(--sc-shadow);
}

.ddl-hero__copy {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ddl-hero__eyebrow {
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
  font-weight: 700;
  letter-spacing: 1.2px;
}

.ddl-hero h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
}

.ddl-hero p {
  max-width: 700px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.ddl-hero__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.ddl-hero__side {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
}

.ddl-hero__art {
  display: grid;
  place-items: center;
  width: 108px;
  height: 92px;
  padding: 6px;
  border-radius: 14px;
  background: #f1f6ff;
}

.ddl-hero__art img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.ddl-hero__actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ddl-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.ddl-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.ddl-toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ddl-toolbar__select {
  width: 240px;
}

.ddl-view-toggle {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--sc-border);
  border-radius: 10px;
  background: var(--sc-surface-muted);
}

.ddl-view-toggle button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  color: var(--sc-text-secondary);
  background: transparent;
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-caption);
  cursor: pointer;
}

.ddl-view-toggle button.is-active {
  color: var(--sc-primary-strong);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow);
  font-weight: 600;
}

.ddl-muted {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.ddl-code {
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 6px;
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  font-size: var(--sc-font-hint);
  font-weight: 600;
}

.ddl-draft {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--sc-border);
}

.ddl-draft:last-child {
  border-bottom: 0;
}

.ddl-draft__title {
  color: var(--sc-text);
  font-size: 14px;
  font-weight: 600;
}

.ddl-inbox {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.ddl-inbox__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--sc-border);
  border-radius: 12px;
  background: var(--sc-warning-soft);
}

.ddl-inbox__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.ddl-inbox__head strong {
  color: var(--sc-text);
  font-size: 14px;
}

.ddl-inbox__time {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 500;
}

.ddl-inbox__reason {
  margin: 0;
  color: #6f5a2e;
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

[data-theme="dark"] .ddl-inbox__reason {
  color: #fbbf24;
}

.ddl-inbox__actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
}

.ddl-countdown {
  margin-top: 4px;
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
}

.ddl-countdown--overdue {
  color: var(--sc-danger);
  font-weight: 600;
}

.ddl-day-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ddl-day-task {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 10px;
  transition: background-color 0.18s ease;
}

.ddl-day-task:hover {
  background: var(--sc-surface-hover);
}

.ddl-day-task__time {
  flex: none;
  width: 48px;
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.ddl-day-task__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ddl-day-task__main strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ddl-day-task__main small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.ddl-dialog__intro {
  margin: 0 0 12px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.ddl-dialog__target {
  margin-bottom: 12px;
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
}

.ddl-dialog__raw {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 12px 0;
}

.ddl-diff__changed {
  color: #c2761a;
  font-weight: 600;
}

[data-theme="dark"] .ddl-diff__changed {
  color: #fbbf24;
}

.ddl-form__control {
  width: 100%;
}

@media (max-width: 1200px) {
  .ddl-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .ddl-hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px;
  }

  .ddl-hero__side {
    width: 100%;
    justify-content: space-between;
  }

  .ddl-hero__actions {
    flex-direction: row;
  }

  .ddl-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 639px) {
  .ddl-hero__art {
    display: none;
  }

  .ddl-toolbar__filters {
    width: 100%;
  }

  .ddl-toolbar__select {
    flex: 1;
    width: auto;
  }

  .ddl-inbox {
    grid-template-columns: 1fr;
  }

  .ddl-draft {
    flex-direction: column;
    align-items: flex-start;
  }

  .ddl-day-task {
    flex-wrap: wrap;
  }

  .ddl-day-task__main {
    flex-basis: calc(100% - 60px);
  }
}
</style>
