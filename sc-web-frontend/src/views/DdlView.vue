<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

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
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
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
  <div>
    <section class="sc-ddl-hero">
      <div class="sc-ddl-hero__copy">
        <span class="sc-ddl-hero__eyebrow">DDL RADAR</span>
        <h2>DDL 待办工作台</h2>
        <p>
          粘贴群通知即可提取结构化待办；模糊时间会进入待确认收件箱，重复通知的截止时间变更必须先确认，
          系统不会静默覆盖已有任务。
        </p>
      </div>
      <div class="sc-ddl-hero__actions">
        <el-button @click="openCreateDialog">手动新建</el-button>
        <el-button type="primary" @click="openExtractDialog">粘贴通知提取</el-button>
      </div>
    </section>

    <div class="sc-ddl-metrics">
      <div class="sc-ddl-metric">
        <span>待确认</span>
        <strong>{{ counts.pending }}</strong>
      </div>
      <div class="sc-ddl-metric">
        <span>变更草案</span>
        <strong>{{ counts.drafts }}</strong>
      </div>
      <div class="sc-ddl-metric">
        <span>进行中</span>
        <strong>{{ counts.active }}</strong>
      </div>
      <div class="sc-ddl-metric sc-ddl-metric--danger">
        <span>已逾期</span>
        <strong>{{ counts.overdue }}</strong>
      </div>
      <div class="sc-ddl-metric">
        <span>已完成</span>
        <strong>{{ counts.completed }}</strong>
      </div>
    </div>

    <el-alert
      class="sc-card-gap"
      type="info"
      :closable="false"
      show-icon
      title="提醒调度已启用"
      description="T-24h、T-6h、逾期与待确认跟进提醒由后台调度自动执行；站内通知始终可用，QQ 推送失败或离线时会降级为仅 Web 展示。"
    />

    <el-card class="sc-card-gap" shadow="never">
      <div class="sc-ddl-toolbar">
        <el-select
          v-model="courseFilter"
          clearable
          filterable
          placeholder="全部课程"
          class="sc-ddl-toolbar__select"
          @change="loadBoard"
        >
          <el-option
            v-for="course in courses"
            :key="course.course_id"
            :label="course.course_name"
            :value="course.course_id"
          />
        </el-select>
        <el-button :disabled="loading" @click="loadBoard">刷新</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="loadError !== ''"
      class="sc-card-gap"
      type="error"
      :closable="false"
      show-icon
      title="DDL 看板加载失败"
      :description="loadError"
    />

    <el-skeleton v-if="loading" class="sc-card-gap" :rows="6" animated />

    <template v-else>
      <el-card
        v-if="board.pending_change_drafts.length > 0"
        class="sc-card-gap"
        shadow="never"
      >
        <template #header>
          <div class="sc-ddl-section__header">
            <span>重复通知变更草案（{{ board.pending_change_drafts.length }}）</span>
            <el-tag size="small" type="warning">需确认后才生效</el-tag>
          </div>
        </template>
        <article
          v-for="draft in board.pending_change_drafts"
          :key="draft.target_task_id"
          class="sc-ddl-draft"
        >
          <div>
            <div class="sc-ddl-draft__title">
              任务 #T{{ draft.target_task_id }} 收到新的截止时间或交付要求
            </div>
            <div class="sc-ddl-muted">
              新截止：{{ draftDeadline(draft) }}
              <span v-if="draftExpiryText(draft) !== null"> · {{ draftExpiryText(draft) }}</span>
            </div>
            <div v-if="draft.raw_time_text !== null" class="sc-ddl-muted">
              原文时间：{{ draft.raw_time_text }}
            </div>
          </div>
          <el-button type="primary" size="small" @click="openDraftFromBoard(draft)">
            查看对比并决策
          </el-button>
        </article>
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>
          <div class="sc-ddl-section__header">
            <span>待确认收件箱（{{ board.pending_inbox_tasks.length }}）</span>
            <span class="sc-ddl-muted">补充时间后进入 T-24h / T-6h / 逾期提醒范围</span>
          </div>
        </template>
        <el-empty
          v-if="board.pending_inbox_tasks.length === 0"
          description="没有待确认的任务。"
          :image-size="72"
        />
        <div v-else class="sc-ddl-inbox">
          <article
            v-for="task in board.pending_inbox_tasks"
            :key="task.task_id"
            class="sc-ddl-inbox__card"
          >
            <header class="sc-ddl-inbox__head">
              <div>
                <span class="sc-ddl-code">{{ task.display_code }}</span>
                <strong>{{ task.task_title }}</strong>
              </div>
              <el-tag size="small" :type="statusTagType(task.status)">
                {{ statusLabel(task.status) }}
              </el-tag>
            </header>
            <div class="sc-ddl-muted">
              {{ task.course_name }} · 时间精度：{{ precisionLabel(task.time_precision) }}
            </div>
            <div class="sc-ddl-inbox__time">
              <div>解析截止：{{ pendingInfos[task.task_id]?.deadline ?? '未生成截止时间' }}</div>
              <div v-if="pendingInfos[task.task_id]?.rawTime !== null">
                原文时间：{{ pendingInfos[task.task_id]?.rawTime }}
              </div>
              <div v-if="pendingInfos[task.task_id]?.timeInferred">
                原通知未注明具体时刻，暂按当日 23:59 统计
              </div>
            </div>
            <p class="sc-ddl-inbox__reason">{{ pendingInfos[task.task_id]?.reason }}</p>
            <div class="sc-ddl-inbox__actions">
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
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>
          <div class="sc-ddl-section__header">
            <span>进行中与已逾期（{{ board.active_tasks.length }}）</span>
            <span class="sc-ddl-muted">按截止时间升序</span>
          </div>
        </template>
        <el-empty
          v-if="board.active_tasks.length === 0"
          description="没有进行中的任务。"
          :image-size="72"
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
                <span class="sc-ddl-code">{{ row.display_code }}</span>
                <strong>{{ row.task_title }}</strong>
              </div>
              <div class="sc-ddl-muted">{{ row.course_name }}</div>
            </template>
          </el-table-column>
          <el-table-column label="截止时间" min-width="240">
            <template #default="{ row }">
              <div>{{ deadlineText(row) }}</div>
              <div class="sc-ddl-muted">
                {{ precisionLabel(row.time_precision) }}
                <span v-if="row.raw_time_text !== null"> · 原文“{{ row.raw_time_text }}”</span>
              </div>
              <el-tag v-if="inferredNotice(row) !== null" size="small" type="info">
                未注明具体时刻，按当日 23:59 统计
              </el-tag>
              <div
                v-if="countdowns[row.task_id] !== undefined && countdowns[row.task_id] !== null"
                class="sc-ddl-countdown"
                :class="{ 'sc-ddl-countdown--overdue': countdowns[row.task_id]?.overdue }"
              >
                {{ countdowns[row.task_id]?.text }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)">
                {{ statusLabel(row.status) }}
              </el-tag>
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
      </el-card>

      <el-card class="sc-card-gap" shadow="never">
        <template #header>
          <div class="sc-ddl-section__header">
            <span>已完成（最近 {{ board.completed_tasks.length }} 条）</span>
          </div>
        </template>
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
                <span class="sc-ddl-code">{{ row.display_code }}</span>
                <strong>{{ row.task_title }}</strong>
              </div>
              <div class="sc-ddl-muted">{{ row.course_name }}</div>
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
      </el-card>
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
.sc-ddl-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 22px 24px;
  border-radius: 16px;
  background: linear-gradient(120deg, #f5f9ff 0%, #eef4ff 55%, #f7f3ff 100%);
  border: 1px solid #e3ecfb;
}

.sc-ddl-hero__eyebrow {
  color: #7590c4;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.6px;
}

.sc-ddl-hero h2 {
  margin: 6px 0 8px;
  color: #1d2b45;
  font-size: 22px;
}

.sc-ddl-hero p {
  max-width: 640px;
  margin: 0;
  color: #5d6b84;
  font-size: 13px;
  line-height: 1.7;
}

.sc-ddl-hero__actions {
  display: flex;
  gap: 10px;
}

.sc-ddl-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 16px 0 0;
}

.sc-ddl-metric {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #edf1f7;
  font-size: 12px;
  color: #7b8794;
}

.sc-ddl-metric strong {
  color: #1d2b45;
  font-size: 18px;
}

.sc-ddl-metric--danger strong {
  color: #d64545;
}

.sc-ddl-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.sc-ddl-toolbar__select {
  width: 240px;
}

.sc-ddl-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.sc-ddl-muted {
  color: #7b8794;
  font-size: 12px;
}

.sc-ddl-code {
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 6px;
  background: #eef4ff;
  color: #3d6fd8;
  font-size: 12px;
  font-weight: 600;
}

.sc-ddl-draft {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px dashed #e7ecf5;
}

.sc-ddl-draft:last-child {
  border-bottom: 0;
}

.sc-ddl-draft__title {
  color: #1d2b45;
  font-size: 14px;
  font-weight: 600;
}

.sc-ddl-inbox {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.sc-ddl-inbox__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid #f0e2c8;
  border-radius: 12px;
  background: #fffdf7;
}

.sc-ddl-inbox__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.sc-ddl-inbox__head strong {
  color: #1d2b45;
  font-size: 14px;
}

.sc-ddl-inbox__reason {
  margin: 0;
  color: #6f5a2e;
  font-size: 12px;
  line-height: 1.7;
}

.sc-ddl-inbox__time {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #1d2b45;
  font-size: 13px;
  font-weight: 500;
}

.sc-ddl-dialog__notice {
  margin-top: 8px;
}

.sc-ddl-inbox__actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
}

.sc-ddl-countdown {
  margin-top: 4px;
  color: #3d6fd8;
  font-size: 12px;
}

.sc-ddl-countdown--overdue {
  color: #d64545;
  font-weight: 600;
}

.sc-ddl-dialog__intro {
  margin: 0 0 12px;
  color: #5d6b84;
  font-size: 13px;
  line-height: 1.7;
}

.sc-ddl-dialog__target {
  margin-bottom: 12px;
  color: #1d2b45;
  font-size: 13px;
}

.sc-ddl-dialog__raw {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 12px 0;
}

.sc-ddl-diff__changed {
  color: #c2761a;
  font-weight: 600;
}

.sc-ddl-form__control {
  width: 100%;
}

@media (max-width: 640px) {
  .sc-ddl-hero {
    padding: 18px;
  }

  .sc-ddl-toolbar__select {
    width: 100%;
  }

  .sc-ddl-draft {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
