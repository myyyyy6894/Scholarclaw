<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listDdlTasks } from '@/api/ddl'
import type { DdlTask } from '@/api/ddl'
import {
  createPersonalEvent, deletePersonalEvent, deleteTaskProfile, getPlanningPreference,
  listPersonalEvents, listTaskProfiles, savePlanningPreference, saveTaskProfile, updatePersonalEvent,
} from '@/api/planning'
import type { EventWrite, PersonalEvent, PlanningPreference, ProfileWrite, StudyWindow, TaskProfile } from '@/api/planning'
import { getUserConfig } from '@/api/user-config'
import BaseCard from '@/components/ui/BaseCard.vue'
import { notifyError, notifySuccess } from '@/utils/notify'
import { formatLocalMinute, parseLocalMinute, requireOffsetTimestamp } from '@/views/planning/planning-time'
import StudyPlans from '@/views/planning/StudyPlans.vue'

const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
type WindowForm = { weekday: number | undefined; start: string; end: string }
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const inputRevision = ref(0)
const preferenceVersion = ref<number | null>(null)
const preference = reactive({ timezone: '', windows: [] as WindowForm[], focus: null as number | null,
  dailyLimit: null as number | null, buffer: null as number | null })
const events = ref<PersonalEvent[]>([])
const profiles = ref<TaskProfile[]>([])
const tasks = ref<DdlTask[]>([])
const profileMap = computed(() => new Map(profiles.value.map((item) => [item.task_id, item])))
async function refreshProgress(): Promise<void> {
  try { profiles.value = await listTaskProfiles(); inputRevision.value++ }
  catch (error) { notifyError(error) }
}

function applyPreference(value: PlanningPreference | null, userTimezone: string): void {
  preferenceVersion.value = value?.version ?? null
  preference.timezone = value?.timezone ?? userTimezone
  preference.focus = value?.focus_minutes ?? null
  preference.dailyLimit = value?.daily_limit_minutes ?? null
  preference.buffer = value?.buffer_minutes ?? null
  preference.windows = value?.windows.map((window) => ({ weekday: window.weekday,
    start: formatLocalMinute(window.start_minute), end: formatLocalMinute(window.end_minute) })) ?? []
}
async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const [stored, eventRows, taskRows, board, user] = await Promise.all([
      getPlanningPreference(), listPersonalEvents(), listTaskProfiles(), listDdlTasks(), getUserConfig(),
    ])
    applyPreference(stored, user.timezone)
    events.value = eventRows
    profiles.value = taskRows
    tasks.value = [...board.pending_inbox_tasks, ...board.active_tasks]
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '规划资料读取失败。'
  } finally { loading.value = false }
}
onMounted(() => { void load() })

function parseRange(start: string, end: string): [number, number] {
  const from = parseLocalMinute(start), to = parseLocalMinute(end, true)
  if (from >= to) throw new Error('结束时间必须晚于开始时间；跨日作息请拆成两条。')
  return [from, to]
}
function requiredNumber(value: number | null, label: string): number {
  if (value === null || !Number.isInteger(value)) throw new Error(`请填写${label}。`)
  return value
}
async function savePreference(): Promise<void> {
  if (saving.value) return
  try {
    if (!preference.timezone.trim()) throw new Error('请填写计划时区。')
    if (!preference.windows.length) throw new Error('请至少添加一段可学习时间。')
    const windows: StudyWindow[] = preference.windows.map((window) => {
      if (!window.weekday) throw new Error('请选择可学习时间的星期。')
      const [start, end] = parseRange(window.start, window.end)
      return { weekday: window.weekday, start_minute: start, end_minute: end }
    })
    saving.value = true
    const result = await savePlanningPreference({ timezone: preference.timezone.trim(), windows,
      focus_minutes: requiredNumber(preference.focus, '专注长度'),
      daily_limit_minutes: requiredNumber(preference.dailyLimit, '每日学习上限'),
      buffer_minutes: requiredNumber(preference.buffer, '缓冲时间'), version: preferenceVersion.value })
    applyPreference(result, preference.timezone)
    inputRevision.value++
    notifySuccess('学习时间已保存。')
  } catch (error) { notifyError(error) } finally { saving.value = false }
}

const eventDialog = ref(false)
const editingEvent = ref<PersonalEvent | null>(null)
const eventForm = reactive({ title: '', timezone: '', startDate: '', endDate: '', weekly: false,
  weekday: undefined as number | undefined, start: '', end: '', hard: true })
function openEvent(event?: PersonalEvent): void {
  editingEvent.value = event ?? null
  Object.assign(eventForm, { title: event?.title ?? '', timezone: event?.timezone ?? preference.timezone,
    startDate: event?.start_date ?? '', endDate: event?.end_date ?? '', weekly: event?.weekday != null,
    weekday: event?.weekday ?? undefined, start: event ? formatLocalMinute(event.start_minute) : '',
    end: event ? formatLocalMinute(event.end_minute) : '', hard: event?.hard ?? true })
  eventDialog.value = true
}
async function saveEvent(): Promise<void> {
  if (saving.value) return
  try {
    if (!eventForm.title.trim() || !eventForm.timezone.trim() || !eventForm.startDate) throw new Error('请填写名称、时区和开始日期。')
    if (eventForm.weekly && !eventForm.weekday) throw new Error('请选择重复日程的星期。')
    const [start, end] = parseRange(eventForm.start, eventForm.end)
    const body: EventWrite = { title: eventForm.title.trim(), timezone: eventForm.timezone.trim(),
      start_date: eventForm.startDate, end_date: eventForm.weekly ? eventForm.endDate || null : null,
      weekday: eventForm.weekly ? eventForm.weekday ?? null : null, start_minute: start, end_minute: end,
      hard: eventForm.hard, version: editingEvent.value?.version ?? null }
    saving.value = true
    if (editingEvent.value) await updatePersonalEvent(editingEvent.value.event_id, body)
    else await createPersonalEvent(body)
    events.value = await listPersonalEvents()
    inputRevision.value++
    eventDialog.value = false
    notifySuccess('日程已保存。')
  } catch (error) { notifyError(error) } finally { saving.value = false }
}
async function removeEvent(event: PersonalEvent): Promise<void> {
  if (saving.value) return
  try { await ElMessageBox.confirm(`删除「${event.title}」？`, '删除日程', { type: 'warning' }) } catch { return }
  saving.value = true
  try {
    await deletePersonalEvent(event.event_id, event.version)
    events.value = await listPersonalEvents()
    inputRevision.value++
    notifySuccess('日程已删除。')
  } catch (error) { notifyError(error) } finally { saving.value = false }
}
function eventWhen(event: PersonalEvent): string {
  const recurrence = event.weekday === null ? event.start_date
    : `${event.start_date} 起${event.end_date ? `至 ${event.end_date}` : ''} 每${weekdays[event.weekday - 1]}`
  return `${recurrence} ${formatLocalMinute(event.start_minute)}–${formatLocalMinute(event.end_minute)} · ${event.timezone}`
}

const profileDialog = ref(false)
const editingTask = ref<DdlTask | null>(null)
const profileForm = reactive({ estimated: null as number | null, remaining: null as number | null,
  priority: 3, earliest: '', splittable: true, minBlock: 30 })
function openProfile(task: DdlTask): void {
  editingTask.value = task
  const profile = profileMap.value.get(task.task_id)
  Object.assign(profileForm, { estimated: profile?.estimated_minutes ?? null, remaining: profile?.remaining_minutes ?? null,
    priority: profile?.priority ?? 3, earliest: profile?.earliest_start_at ?? '', splittable: profile?.splittable ?? true,
    minBlock: profile?.min_block_minutes ?? 30 })
  profileDialog.value = true
}
async function saveProfile(): Promise<void> {
  const task = editingTask.value
  if (!task || saving.value) return
  try {
    const existing = profileMap.value.get(task.task_id)
    const body: ProfileWrite = { estimated_minutes: requiredNumber(profileForm.estimated, '预计工时'),
      remaining_minutes: requiredNumber(profileForm.remaining, '剩余工时'), priority: profileForm.priority,
      earliest_start_at: requireOffsetTimestamp(profileForm.earliest), splittable: profileForm.splittable,
      min_block_minutes: profileForm.minBlock, version: existing?.version ?? null }
    saving.value = true
    await saveTaskProfile(task.task_id, body)
    profiles.value = await listTaskProfiles()
    inputRevision.value++
    profileDialog.value = false
    notifySuccess('任务工时已保存。')
  } catch (error) { notifyError(error) } finally { saving.value = false }
}
async function removeProfile(task: DdlTask): Promise<void> {
  const profile = profileMap.value.get(task.task_id)
  if (!profile || saving.value) return
  try { await ElMessageBox.confirm(`清除「${task.task_title}」的规划工时？DDL 任务本身会保留。`, '清除工时', { type: 'warning' }) }
  catch { return }
  saving.value = true
  try {
    await deleteTaskProfile(task.task_id, profile.version)
    profiles.value = await listTaskProfiles()
    inputRevision.value++
    notifySuccess('工时资料已清除。')
  } catch (error) { notifyError(error) } finally { saving.value = false }
}
function deadline(task: DdlTask): string {
  if (!task.due_date) return '截止时间待确认'
  return `截止 ${task.due_date} ${task.due_time?.slice(0, 5) ?? '当天结束'} · ${task.user_timezone}`
}
</script>

<template>
  <div class="planning-page">
    <div class="planning-heading"><div><h1>时间规划</h1><p>先填写自己的可学习时间、固定日程和任务工时。</p></div></div>
    <p v-if="loading" class="planning-hint">正在读取规划资料…</p>
    <div v-else-if="loadError" role="alert" class="planning-error">{{ loadError }} <el-button @click="load">重试</el-button></div>
    <template v-else>
      <StudyPlans :timezone="preference.timezone" :input-revision="inputRevision" @progress-changed="refreshProgress" />
      <BaseCard title="我的学习时间" subtitle="只在你明确填写的时间内安排学习；免打扰设置不会被当作睡眠。">
        <div class="planning-grid">
          <label>计划时区<el-input v-model="preference.timezone" placeholder="例如 Asia/Shanghai" /></label>
          <label>每次专注（分钟）<el-input-number v-model="preference.focus" :min="5" :max="480" controls-position="right" /></label>
          <label>每日学习上限（分钟）<el-input-number v-model="preference.dailyLimit" :min="5" :max="1440" controls-position="right" /></label>
          <label>课程和日程前后缓冲（分钟）<el-input-number v-model="preference.buffer" :min="0" :max="120" controls-position="right" /></label>
        </div>
        <p class="planning-hint">填写每周可学习时段，结束时间可填 24:00；跨日请拆成两条。</p>
        <div v-for="(window, index) in preference.windows" :key="index" class="window-row">
          <el-select v-model="window.weekday" placeholder="星期" aria-label="星期"><el-option v-for="(day, i) in weekdays" :key="day" :label="day" :value="i + 1" /></el-select>
          <el-input v-model="window.start" placeholder="开始 08:00" aria-label="开始时间" />
          <span>至</span><el-input v-model="window.end" placeholder="结束 24:00" aria-label="结束时间" />
          <el-button text type="danger" @click="preference.windows.splice(index, 1)">删除</el-button>
        </div>
        <div class="planning-actions"><el-button @click="preference.windows.push({ weekday: undefined, start: '', end: '' })">添加可学习时间</el-button>
          <el-button type="primary" :loading="saving" @click="savePreference">保存学习时间</el-button></div>
      </BaseCard>
      <BaseCard title="固定日程" subtitle="睡眠、用餐或其他个人安排可在这里明确添加。">
        <template #actions><el-button @click="openEvent()">添加日程</el-button></template>
        <p v-if="!events.length" class="planning-hint">还没有固定日程。</p>
        <ul v-else class="planning-list"><li v-for="event in events" :key="event.event_id">
          <div><strong>{{ event.title }}</strong><small>{{ eventWhen(event) }} · {{ event.hard ? '必须避开' : '可与学习重叠' }}</small></div>
          <div><el-button text @click="openEvent(event)">编辑</el-button><el-button text type="danger" @click="removeEvent(event)">删除</el-button></div>
        </li></ul>
      </BaseCard>
      <BaseCard title="任务工时" subtitle="分别填写预计和剩余时间；截止日期只是最晚完成时间。">
        <p v-if="!tasks.length" class="planning-hint">还没有待处理的 DDL 任务，可先到 DDL 任务页创建。</p>
        <ul v-else class="planning-list"><li v-for="task in tasks" :key="task.task_id">
          <div><strong>{{ task.task_title }}</strong><small>{{ deadline(task) }}<template v-if="profileMap.get(task.task_id)"> · 剩余 {{ profileMap.get(task.task_id)?.remaining_minutes }} 分钟</template><template v-else> · 尚未填写工时</template></small></div>
          <div><el-button text @click="openProfile(task)">设置工时</el-button><el-button v-if="profileMap.has(task.task_id)" text type="danger" @click="removeProfile(task)">清除</el-button></div>
        </li></ul>
      </BaseCard>
    </template>

    <el-dialog v-model="eventDialog" :title="editingEvent ? '编辑固定日程' : '添加固定日程'" width="min(600px, calc(100vw - 24px))" :close-on-click-modal="false">
      <div class="planning-grid">
        <label>日程名称<el-input v-model="eventForm.title" maxlength="200" /></label>
        <label>时区<el-input v-model="eventForm.timezone" placeholder="Asia/Shanghai" /></label>
        <label>开始日期<el-date-picker v-model="eventForm.startDate" type="date" value-format="YYYY-MM-DD" /></label>
        <label>每周重复<el-switch v-model="eventForm.weekly" /></label>
        <label v-if="eventForm.weekly">星期<el-select v-model="eventForm.weekday" placeholder="请选择"><el-option v-for="(day, i) in weekdays" :key="day" :label="day" :value="i + 1" /></el-select></label>
        <label v-if="eventForm.weekly">结束日期（可不填）<el-date-picker v-model="eventForm.endDate" type="date" value-format="YYYY-MM-DD" clearable /></label>
        <label>开始时间<el-input v-model="eventForm.start" placeholder="例如 08:00" /></label>
        <label>结束时间<el-input v-model="eventForm.end" placeholder="例如 09:00 或 24:00" /></label>
        <label>规划时必须避开<el-switch v-model="eventForm.hard" /></label>
      </div>
      <p class="planning-hint">跨日安排请按两天分别添加；关闭“必须避开”后，该日程可以与学习时间重叠。</p>
      <template #footer><el-button @click="eventDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEvent">保存日程</el-button></template>
    </el-dialog>
    <el-dialog v-model="profileDialog" :title="`设置工时 · ${editingTask?.task_title ?? ''}`" width="min(600px, calc(100vw - 24px))" :close-on-click-modal="false">
      <div class="planning-grid">
        <label>预计总工时（分钟）<el-input-number v-model="profileForm.estimated" :min="1" :max="100000" controls-position="right" /></label>
        <label>剩余工时（分钟）<el-input-number v-model="profileForm.remaining" :min="0" :max="100000" controls-position="right" /></label>
        <label>优先级（1 最高）<el-input-number v-model="profileForm.priority" :min="1" :max="5" controls-position="right" /></label>
        <label>可拆分学习<el-switch v-model="profileForm.splittable" /></label>
        <label>最小时间块（分钟）<el-input-number v-model="profileForm.minBlock" :min="5" :max="480" controls-position="right" /></label>
        <label>最早开始（可不填，须带时区偏移）<el-input v-model="profileForm.earliest" placeholder="2026-11-09T09:00+08:00" /></label>
      </div>
      <p class="planning-hint">只保存规划资料，不修改任务截止日期或自动完成任务。</p>
      <template #footer><el-button @click="profileDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProfile">保存工时</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.planning-page { display: grid; gap: 18px; min-width: 0; }
.planning-heading h1 { margin: 0; font-size: 24px; color: var(--sc-text); }
.planning-heading p, .planning-hint { color: var(--sc-text-secondary); line-height: 1.6; }
.planning-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.planning-grid label { display: grid; gap: 6px; min-width: 0; font-size: 13px; }
.planning-grid :deep(.el-input-number), .planning-grid :deep(.el-date-editor) { width: 100%; }
.window-row, .planning-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 10px 0; }
.window-row .el-select { width: 110px; } .window-row .el-input { width: 130px; }
.planning-list { list-style: none; padding: 0; margin: 0; }
.planning-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 0; border-bottom: 1px solid var(--sc-border); }
.planning-list li:last-child { border-bottom: 0; }
.planning-list small { display: block; margin-top: 5px; color: var(--sc-text-secondary); overflow-wrap: anywhere; }
.planning-error { color: var(--sc-danger); }
@media (max-width: 600px) { .planning-grid { grid-template-columns: 1fr; } .window-row .el-input { width: 100px; } }
</style>
