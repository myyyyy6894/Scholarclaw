<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, Plus } from '@lucide/vue'
import { ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { ApiError } from '@/api/error'
import {
  createScheduleRule, createScheduleTerm, deleteScheduleExam, deleteScheduleException, deleteScheduleRule,
  listOccurrences, listScheduleExams, listScheduleExceptions, listSchedulePeriods, listScheduleRules, listScheduleTerms,
  previewSchedulePeriods, saveScheduleExam, saveScheduleException, saveSchedulePeriods, updateScheduleRule, updateScheduleTerm,
} from '@/api/schedule'
import type {
  ExamWrite, ExceptionWrite, RuleWrite, ScheduleExam, ScheduleException, ScheduleOccurrence, SchedulePeriod, ScheduleRule, ScheduleTerm,
  TermWrite,
} from '@/api/schedule'
import { getUserConfig } from '@/api/user-config'
import BaseCard from '@/components/ui/BaseCard.vue'
import CoursesView from '@/views/CoursesView.vue'
import { generatePeriods } from '@/views/schedule/period-template'
import type { PeriodBlock } from '@/views/schedule/period-template'
import ScheduleImportDialog from '@/views/schedule/ScheduleImportDialog.vue'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { formatClock, formatClockSeconds, mondayOf, parseWeeks, shiftDate, todayInZone, weekDates } from '@/views/schedule/schedule-date'

const route = useRoute()
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const terms = ref<ScheduleTerm[]>([])
const termId = ref('')
const term = computed(() => terms.value.find((item) => item.term_id === termId.value) ?? null)
const periods = ref<SchedulePeriod[]>([])
const rules = ref<ScheduleRule[]>([])
const occurrences = ref<ScheduleOccurrence[]>([])
const exams = ref<ScheduleExam[]>([])
const selectedDate = ref('')
const mode = ref<'week' | 'day'>('week')
const loading = ref(true)
const errorMessage = ref('')
const busy = ref(false)
const importDialog = ref(false)
const importSource = ref<'TEXT' | 'IMAGE' | 'FILE'>('FILE')
const weekNo = ref<number | null>(null)
const nowMs = ref(Date.now())
let generation = 0
let clockTimer: ReturnType<typeof setInterval> | null = null
let lastToday = ''

const days = computed(() => mode.value === 'week' ? weekDates(selectedDate.value) : [selectedDate.value])
const rangeLabel = computed(() => days.value.length === 1
  ? days.value[0] : `${days.value[0]} 至 ${days.value[6]}`)
const today = computed(() => term.value ? todayInZone(term.value.timezone, new Date(nowMs.value)) : '')
const currentTime = computed(() => term.value
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: term.value.timezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(nowMs.value))
  : '')
const nextKey = computed(() => occurrences.value.find((event) => Date.parse(event.start_at) > nowMs.value)?.key ?? '')
const teachingStatus = computed(() => {
  if (!term.value) return ''
  if (weekNo.value !== null) return `第 ${weekNo.value} 教学周`
  return selectedDate.value < term.value.first_monday ? '学期尚未开始' : '教学周已结束'
})

async function refreshTerms(preferred?: string): Promise<void> {
  const items = await listScheduleTerms()
  terms.value = items
  const desired = preferred ?? termId.value
  termId.value = items.some((item) => item.term_id === desired)
    ? desired : (items.find((item) => item.active) ?? items[0])?.term_id ?? ''
  if (term.value && !selectedDate.value) {
    const requested = typeof route.query.date === 'string' ? route.query.date : ''
    const valid = /^\d{4}-\d{2}-\d{2}$/.test(requested)
      && !Number.isNaN(Date.parse(`${requested}T00:00:00Z`))
      && new Date(`${requested}T00:00:00Z`).toISOString().slice(0, 10) === requested
    selectedDate.value = valid ? requested : todayInZone(term.value.timezone)
  }
}

async function loadWorkspace(): Promise<void> {
  const current = term.value
  if (!current || !selectedDate.value) {
    occurrences.value = []
    exams.value = []
    periods.value = []
    rules.value = []
    loading.value = false
    return
  }
  lastToday = todayInZone(current.timezone, new Date(nowMs.value))
  const requestGeneration = ++generation
  loading.value = true
  errorMessage.value = ''
  const dates = mode.value === 'week' ? weekDates(selectedDate.value) : [selectedDate.value]
  try {
    const [nextPeriods, nextRules, result, nextExams] = await Promise.all([
      listSchedulePeriods(current.term_id), listScheduleRules(current.term_id),
      listOccurrences(current.term_id, dates[0], dates[dates.length - 1]),
      listScheduleExams(current.term_id),
    ])
    if (requestGeneration !== generation) return
    periods.value = nextPeriods
    rules.value = nextRules
    exams.value = nextExams
    occurrences.value = result.occurrences
    weekNo.value = result.week_no
  } catch (error) {
    if (requestGeneration !== generation) return
    errorMessage.value = error instanceof Error ? error.message : '课表读取失败，请重试。'
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}

async function refresh(preferred?: string): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    await refreshTerms(preferred)
    await loadWorkspace()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '学期读取失败，请重试。'
    loading.value = false
  }
}
function navigate(direction: number): void {
  selectedDate.value = shiftDate(selectedDate.value, direction * (mode.value === 'week' ? 7 : 1))
  void loadWorkspace()
}
function goToday(): void {
  if (!term.value) return
  selectedDate.value = todayInZone(term.value.timezone)
  void loadWorkspace()
}
function changeMode(value: string | number | boolean | undefined): void {
  if (value === 'week' || value === 'day') {
    mode.value = value
    void loadWorkspace()
  }
}
const examsByRule = computed(() => new Map(exams.value.map((exam) => [exam.rule_id, exam])))
const rulesById = computed(() => new Map(rules.value.map((rule) => [rule.rule_id, rule])))
const timeline = computed(() => {
  const items = [
    ...occurrences.value.map((event) => ({
      key: event.key, date: event.date, time: clock(event.start_at), kind: 'class' as const, event,
    })),
    ...exams.value.map((exam) => ({
      key: `exam-${exam.rule_id}`, date: exam.date, time: exam.start_time.slice(0, 5), kind: 'exam' as const, exam,
    })),
  ]
  return items.sort((a, b) => a.time.localeCompare(b.time))
})
function eventsOn(date: string): typeof timeline.value {
  return timeline.value.filter((item) => item.date === date)
}
function dateLabel(date: string): string {
  const weekday = (new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7
  return `${weekdays[weekday]} ${date.slice(5)}`
}
function clock(value: string): string {
  return term.value ? formatClock(value, term.value.timezone) : ''
}
function eventStatus(event: ScheduleOccurrence): string {
  if (nowMs.value >= Date.parse(event.start_at) && nowMs.value < Date.parse(event.end_at)) return '进行中'
  return event.key === nextKey.value ? '下一节' : ''
}
function showNowBefore(date: string, index: number): boolean {
  if (date !== today.value) return false
  const items = eventsOn(date)
  const next = items.findIndex((item) => item.kind === 'exam'
    ? item.time > currentTime.value : Date.parse(item.event.start_at) > nowMs.value)
  return index === (next < 0 ? items.length : next)
}

// 学期
const termDialog = ref(false)
let returnToImportAfterTerm = false
const editingTerm = ref<ScheduleTerm | null>(null)
const termForm = reactive<TermWrite>({ name: '', first_monday: '', week_count: 16, timezone: 'Asia/Shanghai', active: true })
async function openTermEditor(value?: ScheduleTerm): Promise<void> {
  editingTerm.value = value ?? null
  let timezone = value?.timezone ?? 'Asia/Shanghai'
  if (!value) {
    try { timezone = (await getUserConfig()).timezone || timezone } catch { /* 用户可手动填写 */ }
  }
  Object.assign(termForm, value ? {
    name: value.name, first_monday: value.first_monday, week_count: value.week_count,
    timezone: value.timezone, active: value.active, version: value.version,
  } : {
    name: '', first_monday: mondayOf(todayInZone(timezone)), week_count: 16, timezone, active: true,
    version: undefined, confirm_schedule_change: false,
  })
  termDialog.value = true
}
async function configureImportTerm(minimumWeeks: number): Promise<void> {
  returnToImportAfterTerm = true
  importDialog.value = false
  await openTermEditor(term.value ?? undefined)
  termForm.week_count = Math.max(termForm.week_count, minimumWeeks)
}
function resumeImportAfterTerm(): void {
  if (!returnToImportAfterTerm) return
  returnToImportAfterTerm = false
  importSource.value = 'FILE'
  importDialog.value = Boolean(term.value)
}
async function submitTerm(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const changed = editingTerm.value && rules.value.length > 0 && (
      termForm.first_monday !== editingTerm.value.first_monday || termForm.week_count !== editingTerm.value.week_count
      || termForm.timezone !== editingTerm.value.timezone)
    if (changed) {
      try {
        await ElMessageBox.confirm('更改教学周或时区会影响已有课程日期和时间。确认继续？', '确认学期调整', {
          confirmButtonText: '确认修改', cancelButtonText: '返回检查', type: 'warning',
        })
      } catch { return }
    }
    termForm.confirm_schedule_change = Boolean(changed)
    const saved = editingTerm.value
      ? await updateScheduleTerm(editingTerm.value.term_id, { ...termForm })
      : await createScheduleTerm({ ...termForm })
    selectedDate.value = ''
    await refresh(saved.term_id)
    termDialog.value = false
    notifySuccess('学期已保存。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}

// 节次表：先展示受影响课程，用户确认后才修改。
const periodDialog = ref(false)
const periodDraft = ref<SchedulePeriod[]>([])
let returnToImportAfterPeriods = false
const periodBlocks = ref<Array<PeriodBlock & { label: string; presets?: string[] }>>([])
const periodDuration = ref<number | undefined>(45)
const periodGap = ref<number | undefined>(10)
const replacePeriodTimes = ref(false)
const periodTarget = ref(0)
const periodGenerationError = ref('')
function openPeriods(required = 0): void {
  periodTarget.value = required
  const total = Math.max(required, ...periods.value.map((period) => period.period_no), 8)
  periodBlocks.value = [
    { label: '上午', start: '08:00', count: 4, presets: ['07:50', '08:00', '08:10'] },
    { label: '下午', start: '14:00', count: 4, presets: ['14:00', '14:30'] },
    { label: '晚上', start: '19:00', count: Math.max(0, total - 8) },
  ]
  replacePeriodTimes.value = false
  periodGenerationError.value = ''
  periodDraft.value = periods.value.map((item) => ({ ...item }))
  periodDialog.value = true
}
function configureImportPeriods(required: number): void {
  returnToImportAfterPeriods = true
  importDialog.value = false
  openPeriods(required)
}
function fillPeriods(): void {
  periodGenerationError.value = ''
  try {
    periodDraft.value = generatePeriods(periodBlocks.value, periodDuration.value, periodGap.value,
      periodDraft.value, replacePeriodTimes.value)
    if (periodTarget.value > Math.max(...periodDraft.value.map((period) => period.period_no))) {
      periodGenerationError.value = `草稿需要到第 ${periodTarget.value} 节，请增加节数或手动补齐。`
    }
  } catch (error) {
    periodGenerationError.value = error instanceof Error ? error.message : '无法生成节次。'
  }
}
function resumeImportAfterPeriods(): void {
  if (returnToImportAfterPeriods) {
    returnToImportAfterPeriods = false
    importDialog.value = true
  }
}
function addPeriod(): void {
  const next = Math.max(0, ...periodDraft.value.map((item) => item.period_no)) + 1
  if (next > 30) { notifyWarning('最多配置 30 节。'); return }
  periodDraft.value.push({ period_no: next, start_time: '', end_time: '' })
}
async function submitPeriods(): Promise<void> {
  const current = term.value
  if (!current || busy.value) return
  busy.value = true
  try {
    const body = { version: current.version, periods: periodDraft.value, confirm_affected: false }
    const preview = await previewSchedulePeriods(current.term_id, body)
    if (preview.affected.length > 0) {
      const lines = preview.affected.map((item) => `${item.title}：${item.old_start.slice(0, 5)}–${item.old_end.slice(0, 5)} 改为 ${item.new_start.slice(0, 5)}–${item.new_end.slice(0, 5)}`)
      try {
        await ElMessageBox.confirm(`以下课程时间会改变：\n${lines.join('\n')}`, '确认节次调整', {
          confirmButtonText: '确认修改', cancelButtonText: '返回检查', type: 'warning',
        })
      } catch { return }
      body.confirm_affected = true
    }
    await saveSchedulePeriods(current.term_id, body)
    await refresh(current.term_id)
    periodDialog.value = false
    notifySuccess('节次已保存。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}

// 课程规则
const ruleDialog = ref(false)
const editingRule = ref<ScheduleRule | null>(null)
const courseOptions = ref<CourseNamespace[]>([])
const ruleForm = reactive({
  title: '', teacher: '', room: '', courseId: '', weekday: 1, weekText: '1-16',
  timeMode: 'time', startTime: '08:00:00', endTime: '09:00:00',
  startPeriod: 1, endPeriod: 2,
})
function openRuleEditor(value?: ScheduleRule): void {
  editingRule.value = value ?? null
  Object.assign(ruleForm, value ? {
    title: value.title, teacher: value.teacher ?? '', room: value.room ?? '', courseId: value.course_id ?? '',
    weekday: value.weekday, weekText: value.weeks.join(','), timeMode: value.start_period === null ? 'time' : 'period',
    startTime: value.start_time, endTime: value.end_time,
    startPeriod: value.start_period ?? 1, endPeriod: value.end_period ?? 2,
  } : {
    title: '', teacher: '', room: '', courseId: '', weekday: 1, weekText: `1-${term.value?.week_count ?? 16}`,
    timeMode: 'time', startTime: '08:00:00', endTime: '09:00:00', startPeriod: 1, endPeriod: 2,
  })
  ruleDialog.value = true
  void listCourses().then((items) => { courseOptions.value = items }).catch(() => { courseOptions.value = [] })
}
async function submitRule(): Promise<void> {
  const current = term.value
  if (!current || busy.value) return
  let weeks: number[]
  try { weeks = parseWeeks(ruleForm.weekText, current.week_count) }
  catch (error) { notifyWarning(error instanceof Error ? error.message : '周次格式不正确。'); return }
  const body: RuleWrite = {
    term_id: current.term_id, course_id: ruleForm.courseId || null, title: ruleForm.title,
    teacher: ruleForm.teacher || null, room: ruleForm.room || null, weekday: ruleForm.weekday,
    start_time: ruleForm.timeMode === 'time' ? ruleForm.startTime : null,
    end_time: ruleForm.timeMode === 'time' ? ruleForm.endTime : null,
    start_period: ruleForm.timeMode === 'period' ? ruleForm.startPeriod : null,
    end_period: ruleForm.timeMode === 'period' ? ruleForm.endPeriod : null,
    weeks, version: editingRule.value?.version, confirm_overlap: false,
  }
  busy.value = true
  const save = () => editingRule.value ? updateScheduleRule(editingRule.value.rule_id, body) : createScheduleRule(body)
  try {
    try { await save() }
    catch (error) {
      if (!(error instanceof ApiError && error.code === 'STATE_CONFLICT' && error.message.includes('重叠'))) throw error
      await ElMessageBox.confirm(error.message, '课程时间重叠', { confirmButtonText: '仍要保存', cancelButtonText: '返回修改', type: 'warning' })
      body.confirm_overlap = true
      await save()
    }
    ruleDialog.value = false
    await refresh(current.term_id)
    notifySuccess('课程已保存。')
  } catch (error) { if (error !== 'cancel' && error !== 'close') notifyError(error) }
  finally { busy.value = false }
}
const examRule = ref<ScheduleRule | null>(null)
const examDialog = ref(false)
const examForm = reactive<ExamWrite>({ date: '', start_time: '', end_time: '', room: '', version: 0 })
function openExam(rule?: ScheduleRule): void {
  if (!rule) return
  const exam = examsByRule.value.get(rule.rule_id)
  examRule.value = rule
  Object.assign(examForm, exam ? { ...exam } : {
    date: selectedDate.value, start_time: '09:00:00', end_time: '11:00:00', room: '', version: 0,
  })
  examDialog.value = true
}
async function submitExam(): Promise<void> {
  const rule = examRule.value
  if (!rule || busy.value) return
  busy.value = true
  try {
    const { date, start_time, end_time, room, version } = examForm
    await saveScheduleExam(rule.rule_id, { date, start_time, end_time, room: room || null, version })
    selectedDate.value = date
    examDialog.value = false
    await loadWorkspace()
    notifySuccess('考试已加入课表。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
async function removeExam(): Promise<void> {
  const rule = examRule.value
  if (!rule || busy.value || examForm.version === 0) return
  try { await ElMessageBox.confirm(`删除「${rule.title}」的考试安排？`, '删除考试', { type: 'warning' }) }
  catch { return }
  busy.value = true
  try {
    await deleteScheduleExam(rule.rule_id, examForm.version)
    examDialog.value = false
    await loadWorkspace()
    notifySuccess('考试已删除。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}

async function removeRule(rule: ScheduleRule): Promise<void> {
  try { await ElMessageBox.confirm(`删除「${rule.title}」及其考试和停调课记录？`, '删除课程', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) }
  catch { return }
  try {
    await deleteScheduleRule(rule.rule_id, rule.version)
    await refresh(termId.value)
    notifySuccess('课程已删除。')
  } catch (error) { notifyError(error) }
}

// 单次停课、调课与例外恢复
const detailDialog = ref(false)
const selectedEvent = ref<ScheduleOccurrence | null>(null)
const eventExceptions = ref<ScheduleException[]>([])
const exceptionDialog = ref(false)
const exceptionRule = ref<ScheduleRule | null>(null)
const exceptionList = ref<ScheduleException[]>([])
const moveDialog = ref(false)
const moveForm = reactive({ targetDate: '', startTime: '', endTime: '', room: '' })
async function openEvent(event: ScheduleOccurrence): Promise<void> {
  selectedEvent.value = event
  try { eventExceptions.value = await listScheduleExceptions(event.rule_id); detailDialog.value = true }
  catch (error) { notifyError(error) }
}
function eventVersion(): number | undefined {
  return eventExceptions.value.find((item) => item.original_date === selectedEvent.value?.original_date)?.version
}
async function stopEvent(): Promise<void> {
  const event = selectedEvent.value
  if (!event) return
  try { await ElMessageBox.confirm(`只取消 ${event.original_date} 这一次「${event.title}」？`, '停课', { type: 'warning', confirmButtonText: '确认停课', cancelButtonText: '返回' }) }
  catch { return }
  await submitException({ kind: 'CANCEL', target_date: null, start_time: null, end_time: null, room: null,
    version: eventVersion(), confirm_overlap: false })
}
function openMove(): void {
  const event = selectedEvent.value
  if (!event) return
  if (!term.value) return
  Object.assign(moveForm, { targetDate: event.date, startTime: formatClockSeconds(event.start_at, term.value.timezone),
    endTime: formatClockSeconds(event.end_at, term.value.timezone), room: event.room ?? '' })
  moveDialog.value = true
}
async function submitMove(): Promise<void> {
  await submitException({ kind: 'MOVE', target_date: moveForm.targetDate, start_time: moveForm.startTime,
    end_time: moveForm.endTime, room: moveForm.room || null, version: eventVersion(), confirm_overlap: false })
}
async function submitException(body: ExceptionWrite): Promise<void> {
  const event = selectedEvent.value
  if (!event || busy.value) return
  busy.value = true
  try {
    try { await saveScheduleException(event.rule_id, event.original_date, body) }
    catch (error) {
      if (!(error instanceof ApiError && error.code === 'STATE_CONFLICT' && error.message.includes('重叠'))) throw error
      await ElMessageBox.confirm(error.message, '调课时间重叠', { confirmButtonText: '仍要保存', cancelButtonText: '返回修改', type: 'warning' })
      body.confirm_overlap = true
      await saveScheduleException(event.rule_id, event.original_date, body)
    }
    detailDialog.value = false
    moveDialog.value = false
    await refresh(termId.value)
    notifySuccess('停调课已保存。')
  } catch (error) { if (error !== 'cancel' && error !== 'close') notifyError(error) }
  finally { busy.value = false }
}
async function openExceptions(rule: ScheduleRule): Promise<void> {
  try {
    exceptionRule.value = rule
    exceptionList.value = await listScheduleExceptions(rule.rule_id)
    exceptionDialog.value = true
  } catch (error) { notifyError(error) }
}
async function removeException(item: ScheduleException): Promise<void> {
  try { await ElMessageBox.confirm(`撤销 ${item.original_date} 的停调课，恢复原课程？`, '撤销例外', { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '返回' }) }
  catch { return }
  try {
    await deleteScheduleException(item.rule_id, item.original_date, item.version)
    exceptionList.value = await listScheduleExceptions(item.rule_id)
    await refresh(termId.value)
    notifySuccess('已恢复原课程。')
  } catch (error) { notifyError(error) }
}

async function jumpToCourses(): Promise<void> {
  if (route.hash !== '#courses') return
  await nextTick()
  document.getElementById('courses')?.scrollIntoView({ block: 'start' })
}
watch(() => route.hash, jumpToCourses)
onMounted(() => {
  void jumpToCourses()
  if (window.matchMedia('(max-width: 720px)').matches) mode.value = 'day'
  void refresh()
  clockTimer = setInterval(() => {
    nowMs.value = Date.now()
    if (!term.value) return
    const current = todayInZone(term.value.timezone, new Date(nowMs.value))
    if (lastToday && current !== lastToday && selectedDate.value === lastToday) {
      selectedDate.value = current
      void loadWorkspace()
    }
    lastToday = current
  }, 60_000)
})
onUnmounted(() => { if (clockTimer !== null) clearInterval(clockTimer) })
</script>

<template>
  <div class="schedule-page">
    <div class="schedule-heading">
      <div>
        <h1>教学课表</h1>
      </div>
      <div class="schedule-actions">
        <el-button @click="openTermEditor()">新建学期</el-button>
        <el-button :disabled="loading" @click="term ? (importSource = 'FILE', importDialog = true) : configureImportTerm(0)">上传学校导出</el-button>
        <el-button v-if="term" @click="importSource = 'TEXT'; importDialog = true">导入文字</el-button>
        <el-button v-if="term" @click="importSource = 'IMAGE'; importDialog = true">导入图片</el-button>
        <el-button v-if="term" type="primary" @click="openRuleEditor()"><Plus :size="16" /> 添加课程</el-button>
      </div>
    </div>

    <BaseCard v-if="!loading && !term && !errorMessage" title="先设置学期">
      <p class="schedule-hint">填写第一教学周的周一、总周数和时区，就可以手动添加课程。</p>
      <el-button type="primary" @click="openTermEditor()">新建学期</el-button>
    </BaseCard>
    <BaseCard v-else>
      <div class="schedule-toolbar">
        <div class="schedule-toolbar__term">
          <el-select v-model="termId" aria-label="选择学期" placeholder="选择学期" @change="loadWorkspace">
            <el-option v-for="item in terms" :key="item.term_id" :label="`${item.name}${item.active ? '（当前）' : ''}`" :value="item.term_id" />
          </el-select>
          <el-button v-if="term" @click="openTermEditor(term)">学期设置</el-button>
          <el-button v-if="term" @click="openPeriods()">节次时间</el-button>
        </div>
        <div v-if="term" class="schedule-toolbar__nav">
          <el-button aria-label="上一段日期" @click="navigate(-1)"><ChevronLeft :size="16" /></el-button>
          <strong>{{ rangeLabel }}</strong>
          <el-button aria-label="下一段日期" @click="navigate(1)"><ChevronRight :size="16" /></el-button>
          <el-button @click="goToday">今天</el-button>
          <el-radio-group :model-value="mode" aria-label="课表显示方式" @change="changeMode">
            <el-radio-button value="week">周</el-radio-button><el-radio-button value="day">日</el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <p v-if="term" class="schedule-meta">
        {{ term.name }} · {{ teachingStatus }} · 时区 {{ term.timezone }}
      </p>
      <div v-if="loading" class="schedule-empty">正在读取课表…</div>
      <div v-else-if="errorMessage" class="schedule-empty" role="alert">
        <p>{{ errorMessage }}</p><el-button @click="refresh()">重试</el-button>
      </div>
      <div v-else-if="term" class="schedule-grid" :class="{ 'schedule-grid--day': mode === 'day' }">
        <section v-for="date in days" :key="date" class="schedule-day" :class="{ 'schedule-day--today': date === today }">
          <h2>{{ dateLabel(date) }} <span v-if="date === today">今天</span></h2>
          <p v-if="eventsOn(date).length === 0" class="schedule-day__empty">无安排</p>
          <template v-for="(item, index) in eventsOn(date)" :key="item.key">
            <div v-if="showNowBefore(date, index)" class="schedule-now">现在 {{ currentTime }}</div>
            <button v-if="item.kind === 'class'" type="button" class="schedule-event"
              :class="{ 'schedule-event--conflict': item.event.conflict }" @click="openEvent(item.event)">
              <span class="schedule-event__time">{{ clock(item.event.start_at) }}–{{ clock(item.event.end_at) }}</span>
              <strong>{{ item.event.title }}</strong>
              <small>{{ item.event.room || '教室未填写' }}</small>
              <small>{{ item.event.teacher || '教师未填写' }}</small>
              <span v-if="eventStatus(item.event)" class="schedule-event__moved">{{ eventStatus(item.event) }}</span>
              <span v-if="item.event.conflict" class="schedule-event__warning">时间重叠</span>
              <span v-else-if="item.event.moved" class="schedule-event__moved">已调课</span>
            </button>
            <button v-else type="button" class="schedule-event schedule-event--exam"
              @click="openExam(rulesById.get(item.exam.rule_id))">
              <span class="schedule-event__time">{{ item.time }}–{{ item.exam.end_time.slice(0, 5) }}</span>
              <strong>{{ rulesById.get(item.exam.rule_id)?.title }}</strong>
              <small v-if="item.exam.room">{{ item.exam.room }}</small>
              <span class="schedule-event__exam-label">考试</span>
            </button>
          </template>
          <div v-if="showNowBefore(date, eventsOn(date).length)" class="schedule-now">现在 {{ currentTime }}</div>
        </section>
      </div>
    </BaseCard>

    <BaseCard v-if="term" title="课程规则" subtitle="编辑周期课程，或查看已停课和调课的记录">
      <div v-if="rules.length === 0" class="schedule-empty">还没有课程，点击“添加课程”开始。</div>
      <ul v-else class="rule-list">
        <li v-for="rule in rules" :key="rule.rule_id">
          <div><strong>{{ rule.title }}</strong><small>{{ weekdays[rule.weekday - 1] }} {{ rule.start_time.slice(0, 5) }}–{{ rule.end_time.slice(0, 5) }} · 第 {{ rule.weeks.join('、') }} 周</small></div>
          <div class="rule-actions">
            <router-link v-if="rule.course_id" :to="{ name: 'rag', query: { course: rule.course_id } }" class="schedule-link">AI 问答</router-link>
            <el-button v-else text @click="openRuleEditor(rule)">关联课程空间</el-button>
            <el-button text @click="openExam(rule)">{{ examsByRule.has(rule.rule_id) ? '编辑考试' : '添加考试' }}</el-button>
            <el-button text @click="openExceptions(rule)">停调课记录</el-button>
            <el-button text @click="openRuleEditor(rule)">编辑</el-button>
            <el-button text type="danger" @click="removeRule(rule)">删除</el-button>
          </div>
        </li>
      </ul>
    </BaseCard>

    <CoursesView id="courses" />

    <ScheduleImportDialog v-if="term" v-model="importDialog" :term="term" :rules="rules" :periods="periods" :source-type="importSource" @saved="refresh(termId)" @configure-periods="configureImportPeriods" @configure-term="configureImportTerm" />

    <el-dialog v-model="termDialog" :title="editingTerm ? '编辑学期' : '新建学期'" width="520px" class="schedule-dialog" @closed="resumeImportAfterTerm">
      <el-form label-position="top">
        <el-form-item label="学期名称"><el-input v-model="termForm.name" maxlength="100" placeholder="例如 2026–2027 学年秋季" /></el-form-item>
        <el-form-item label="第一教学周的周一"><el-date-picker v-model="termForm.first_monday" type="date" value-format="YYYY-MM-DD" placeholder="选择周一" /></el-form-item>
        <el-form-item label="总教学周数"><el-input-number v-model="termForm.week_count" :min="1" :max="53" /></el-form-item>
        <el-form-item label="课表时区"><el-input v-model="termForm.timezone" placeholder="例如 Asia/Shanghai" /><small class="schedule-hint">课程按该时区的当地时间显示。</small></el-form-item>
        <el-checkbox v-model="termForm.active">设为当前学期</el-checkbox>
      </el-form>
      <template #footer><el-button @click="termDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="submitTerm">保存学期</el-button></template>
    </el-dialog>

    <el-dialog v-model="periodDialog" title="设置节次时间" width="620px" top="4vh" class="schedule-dialog" @closed="resumeImportAfterPeriods">
      <div class="period-template">
        <h3>按作息生成节次</h3>
        <p v-if="periodTarget" class="schedule-hint">这份课表需要填写到第 {{ periodTarget }} 节。</p>
        <div v-for="block in periodBlocks" :key="block.label" class="period-block">
          <strong>{{ block.label }}</strong>
          <label>首节开始 <el-time-picker v-model="block.start" format="HH:mm" value-format="HH:mm" /></label>
          <label>节数 <el-input-number v-model="block.count" :min="0" :max="30" controls-position="right" /></label>
          <div v-if="block.presets" class="period-presets" :aria-label="`${block.label}常用开始时间`" role="group">
            <el-button v-for="time in block.presets" :key="time" size="small" plain
              :type="block.start === time ? 'primary' : 'default'" :aria-pressed="block.start === time"
              @click="block.start = time">{{ time }}</el-button>
          </div>
        </div>
        <div class="period-block period-block--timing">
          <label>每节课（分钟） <el-input-number v-model="periodDuration" :min="1" :max="180" controls-position="right" /></label>
          <label>课间（分钟） <el-input-number v-model="periodGap" :min="0" :max="120" controls-position="right" /></label>
        </div>
        <div class="period-block period-block--actions">
          <el-button @click="fillPeriods">生成节次</el-button>
          <el-checkbox v-if="periodDraft.length" v-model="replacePeriodTimes">覆盖已有时间</el-checkbox>
        </div>
        <p v-if="periodGenerationError" role="alert" class="schedule-hint">{{ periodGenerationError }}</p>
      </div>
      <p class="schedule-hint">生成后可逐节调整。修改已使用的节次时，会先显示受影响的课程。</p>
      <div v-if="periodDraft.length" class="period-list" aria-label="节次预览">
        <div v-for="(period, index) in periodDraft" :key="index" class="period-row">
          <span>第</span><el-input-number v-model="period.period_no" :min="1" :max="30" controls-position="right" /><span>节</span>
          <el-time-picker v-model="period.start_time" format="HH:mm" value-format="HH:mm:ss" placeholder="开始" />
          <span>至</span><el-time-picker v-model="period.end_time" format="HH:mm" value-format="HH:mm:ss" placeholder="结束" />
          <el-button text type="danger" @click="periodDraft.splice(index, 1)">移除</el-button>
        </div>
      </div>
      <el-button @click="addPeriod">添加节次</el-button>
      <template #footer><el-button @click="periodDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="submitPeriods">保存节次</el-button></template>
    </el-dialog>

    <el-dialog v-model="examDialog" :title="`考试安排 · ${examRule?.title ?? ''}`" width="520px" class="schedule-dialog">
      <el-form label-position="top">
        <el-form-item label="考试日期"><el-date-picker v-model="examForm.date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <div class="form-columns">
          <el-form-item label="考试开始"><el-time-picker v-model="examForm.start_time" format="HH:mm" value-format="HH:mm:ss" /></el-form-item>
          <el-form-item label="考试结束"><el-time-picker v-model="examForm.end_time" format="HH:mm" value-format="HH:mm:ss" /></el-form-item>
        </div>
        <el-form-item label="考场（可选）"><el-input v-model="examForm.room" maxlength="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="examForm.version" text type="danger" :disabled="busy" @click="removeExam">删除考试</el-button>
        <el-button @click="examDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitExam">保存考试</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ruleDialog" :title="editingRule ? '编辑课程' : '添加课程'" width="620px" class="schedule-dialog">
      <el-form label-position="top">
        <el-form-item label="课程名称"><el-input v-model="ruleForm.title" maxlength="200" /></el-form-item>
        <div class="form-columns"><el-form-item label="教师（可选）"><el-input v-model="ruleForm.teacher" maxlength="100" /></el-form-item><el-form-item label="教室（可选）"><el-input v-model="ruleForm.room" maxlength="200" /></el-form-item></div>
        <el-form-item label="关联知识库课程（可选）"><el-select v-model="ruleForm.courseId" clearable placeholder="不关联也可以使用课表"><el-option v-for="course in courseOptions" :key="course.course_id" :label="course.course_name" :value="course.course_id" /></el-select></el-form-item>
        <div class="form-columns"><el-form-item label="星期"><el-select v-model="ruleForm.weekday"><el-option v-for="(day, index) in weekdays" :key="day" :label="day" :value="index + 1" /></el-select></el-form-item><el-form-item label="上课周次"><el-input v-model="ruleForm.weekText" placeholder="例如 1-16单周、18" /></el-form-item></div>
        <el-form-item label="上课时间"><el-radio-group v-model="ruleForm.timeMode"><el-radio-button value="time">按时刻</el-radio-button><el-radio-button value="period">按节次</el-radio-button></el-radio-group></el-form-item>
        <div v-if="ruleForm.timeMode === 'time'" class="form-columns"><el-form-item label="开始"><el-time-picker v-model="ruleForm.startTime" format="HH:mm" value-format="HH:mm:ss" /></el-form-item><el-form-item label="结束"><el-time-picker v-model="ruleForm.endTime" format="HH:mm" value-format="HH:mm:ss" /></el-form-item></div>
        <div v-else class="form-columns"><el-form-item label="开始节次"><el-input-number v-model="ruleForm.startPeriod" :min="1" :max="30" /></el-form-item><el-form-item label="结束节次"><el-input-number v-model="ruleForm.endPeriod" :min="1" :max="30" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="ruleDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="submitRule">保存课程</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailDialog" title="课程详情" width="480px" class="schedule-dialog">
      <template v-if="selectedEvent"><h2>{{ selectedEvent.title }}</h2><p>{{ selectedEvent.date }} {{ clock(selectedEvent.start_at) }}–{{ clock(selectedEvent.end_at) }}</p><p>教师：{{ selectedEvent.teacher || '未填写' }}　教室：{{ selectedEvent.room || '未填写' }}</p><p v-if="selectedEvent.moved" class="schedule-hint">原定 {{ selectedEvent.original_date }}，已调课。</p><p v-if="selectedEvent.conflict" class="schedule-warning">与其他课程时间重叠。</p></template>
      <template #footer><el-button @click="detailDialog = false">关闭</el-button><el-button @click="stopEvent">停课</el-button><el-button type="primary" @click="openMove">调课</el-button></template>
    </el-dialog>
    <el-dialog v-model="moveDialog" title="调整这一次课程" width="480px" class="schedule-dialog">
      <el-form label-position="top"><el-form-item label="新日期"><el-date-picker v-model="moveForm.targetDate" type="date" value-format="YYYY-MM-DD" /></el-form-item><div class="form-columns"><el-form-item label="开始"><el-time-picker v-model="moveForm.startTime" format="HH:mm" value-format="HH:mm:ss" /></el-form-item><el-form-item label="结束"><el-time-picker v-model="moveForm.endTime" format="HH:mm" value-format="HH:mm:ss" /></el-form-item></div><el-form-item label="新教室（可选）"><el-input v-model="moveForm.room" /></el-form-item></el-form>
      <template #footer><el-button @click="moveDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="submitMove">保存调课</el-button></template>
    </el-dialog>
    <el-dialog v-model="exceptionDialog" :title="`${exceptionRule?.title ?? ''}的停调课记录`" width="560px" class="schedule-dialog">
      <p v-if="exceptionList.length === 0" class="schedule-hint">暂无停调课记录。</p>
      <ul v-else class="rule-list"><li v-for="item in exceptionList" :key="item.exception_id"><div><strong>{{ item.original_date }} {{ item.kind === 'CANCEL' ? '停课' : '调课' }}</strong><small v-if="item.target_date">调整到 {{ item.target_date }} {{ item.start_time?.slice(0, 5) }}–{{ item.end_time?.slice(0, 5) }}</small></div><el-button text type="danger" @click="removeException(item)">撤销</el-button></li></ul>
      <template #footer><el-button @click="exceptionDialog = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.schedule-page { display: flex; flex-direction: column; gap: var(--sc-card-gap); }
.schedule-heading, .schedule-actions, .schedule-toolbar, .schedule-toolbar__term, .schedule-toolbar__nav { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.schedule-heading, .schedule-toolbar { justify-content: space-between; }
.schedule-heading h1 { margin: 0 0 6px; font-size: var(--sc-font-title); }
.schedule-heading p, .schedule-meta, .schedule-hint { color: var(--sc-text-secondary); font-size: var(--sc-font-caption); }
.schedule-heading p { margin: 0; }
.schedule-toolbar__term .el-select { width: 190px; }
.schedule-toolbar__nav strong { min-width: 160px; text-align: center; font-size: var(--sc-font-body); }
.schedule-meta { margin: 16px 0 12px; }
.schedule-grid { display: grid; grid-template-columns: repeat(7, minmax(120px, 1fr)); overflow-x: auto; border: 1px solid var(--sc-border); border-radius: 12px; }
.schedule-grid--day { grid-template-columns: 1fr; }
.schedule-day { min-height: 210px; padding: 10px; border-right: 1px solid var(--sc-border); }
.schedule-day:last-child { border-right: 0; }
.schedule-day--today { background: var(--sc-surface-hover); }
.schedule-day h2 { margin: 0 0 12px; font-size: var(--sc-font-caption); font-weight: 600; color: var(--sc-text-secondary); }
.schedule-day h2 span { color: var(--sc-primary); }
.schedule-day__empty { color: var(--sc-text-placeholder); font-size: var(--sc-font-caption); }
.schedule-event { display: flex; flex-direction: column; gap: 5px; width: 100%; margin-bottom: 8px; padding: 10px; border: 0; border-left: 3px solid var(--sc-primary); border-radius: 8px; background: var(--sc-primary-soft); color: var(--sc-text); text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.schedule-event:focus-visible { outline: 2px solid var(--sc-primary-strong); outline-offset: 2px; }
.schedule-event small, .schedule-event__time { color: var(--sc-text-secondary); font-size: var(--sc-font-caption); }
.schedule-event--conflict { border-left-color: var(--sc-danger); }
.schedule-event__warning, .schedule-warning { color: var(--sc-danger); font-size: var(--sc-font-caption); }
.schedule-event__moved { color: var(--sc-primary-strong); font-size: var(--sc-font-caption); }
.schedule-now { display: flex; align-items: center; gap: 5px; margin: 8px 0; color: var(--sc-danger); font-size: var(--sc-font-hint); }
.schedule-now::after { content: ''; flex: 1; border-top: 1px solid currentColor; }
.schedule-empty { padding: 28px 8px; color: var(--sc-text-secondary); text-align: center; }
.rule-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.rule-actions .el-button + .el-button { margin-left: 0; }
.schedule-link { font-size: 13px; color: var(--sc-primary); padding: 6px 8px; }
.schedule-event--exam { border-inline-start: 3px solid var(--sc-warning); }
.schedule-event__exam-label { color: var(--sc-text); background: var(--sc-warning-soft); font-weight: 600; font-size: 12px; }
.rule-list { margin: 0; padding: 0; list-style: none; }
.rule-list li { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--sc-border); }
.rule-list li:last-child { border-bottom: 0; }
.rule-list strong, .rule-list small { display: block; }
.rule-list small { margin-top: 5px; color: var(--sc-text-secondary); }
.form-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.period-template { padding: 14px 16px; background: var(--sc-surface-muted); border: 1px solid var(--sc-border); border-radius: 10px; }
.period-template h3 { margin: 0 0 10px; font-size: 15px; }
.period-block { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin-top: 8px; }
.period-block strong { min-width: 2.5em; }
.period-block label { display: inline-flex; align-items: center; gap: 6px; }
.period-block :deep(.el-date-editor), .period-block .el-input-number { width: 112px; }
.period-presets { display: flex; gap: 4px; flex-wrap: wrap; }
.period-presets .el-button + .el-button { margin-left: 0; }
.period-block--actions { margin-top: 12px; }
.period-list { max-height: min(29vh, 300px); overflow-y: auto; padding: 3px 8px 3px 0; margin-bottom: 8px; }
.period-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.period-row .el-input-number { width: 100px; flex: none; }
.period-row .el-date-editor { min-width: 0; }
:deep(.schedule-dialog) { max-width: calc(100vw - 32px); }
@media (max-width: 720px) {
  .schedule-heading, .schedule-toolbar { align-items: stretch; }
  .schedule-toolbar__term, .schedule-toolbar__nav { width: 100%; }
  .schedule-toolbar__nav { display: grid; grid-template-columns: 40px minmax(120px, 1fr) 40px auto; gap: 8px; }
  .schedule-toolbar__nav strong { min-width: 0; white-space: normal; word-break: keep-all; font-size: var(--sc-font-caption); }
  .schedule-toolbar__nav .el-radio-group { grid-column: 1 / -1; }
  .rule-list li { align-items: flex-start; flex-direction: column; }
  .period-row { flex-wrap: wrap; }
  .period-row .el-date-editor { width: 120px; }
}
</style>
