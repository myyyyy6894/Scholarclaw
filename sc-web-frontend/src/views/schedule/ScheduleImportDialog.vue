<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/error'
import type { RuleWrite, SchedulePeriod, ScheduleRule, ScheduleTerm } from '@/api/schedule'
import {
  cancelScheduleImport, confirmScheduleImport, createImageScheduleImport, createTextScheduleImport,
  getScheduleImageModel, getScheduleImport, getScheduleImportImage, listScheduleImports,
  retryImageScheduleImport, retryScheduleImport,
} from '@/api/schedule-import'
import type { ScheduleImageModel, ScheduleImport, ScheduleImportReceipt } from '@/api/schedule-import'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { editorToRule, importConflicts, operationFor, suggestionToEditor } from '@/views/schedule/schedule-import-form'
import type { ImportEditor, ImportOperation } from '@/views/schedule/schedule-import-form'

const props = defineProps<{
  modelValue: boolean
  term: ScheduleTerm
  rules: ScheduleRule[]
  periods: SchedulePeriod[]
  sourceType: 'TEXT' | 'IMAGE'
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: []; 'configure-periods': [] }>()
const visible = computed({ get: () => props.modelValue, set: (value: boolean) => emit('update:modelValue', value) })
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const rawText = ref('')
const imageFile = ref<File | null>(null)
const previewUrl = ref('')
const recent = ref<ScheduleImport[]>([])
const task = ref<ScheduleImport | null>(null)
const editors = ref<ImportEditor[]>([])
const busy = ref(false)
const message = ref('')
const createOperation = ref<ImportOperation | null>(null)
const confirmOperation = ref<ImportOperation | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null
let generation = 0
let lastTermId = props.term.term_id
let lastSourceType = props.sourceType
let previewGeneration = 0
let imageOperation: { file: File; termId: string; consentToken: string; requestId: string } | null = null
// 校对修改留在当前页面内，切换任务或关闭弹窗后可恢复；服务端版本变化时不能套用旧修改。
const localDrafts = new Map<string, ImportEditor[]>()

const deadline = computed(() => task.value
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: props.term.timezone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(task.value.expires_at))
  : '')
const selectedCount = computed(() => editors.value.filter((item) => item.included).length)
const missingPeriods = computed(() => {
  const defined = new Set(props.periods.map((period) => period.period_no))
  return [...new Set(editors.value.filter((row) => row.included && row.timeMode === 'period')
    .flatMap((row) => [row.startPeriod, row.endPeriod])
    .filter((period): period is number => period !== undefined && !defined.has(period)))].sort((a, b) => a - b)
})
const scheduleChanged = computed(() => task.value !== null && task.value.schedule_version !== props.term.schedule_version)
const visibleRecent = computed(() => recent.value.filter((item) => item.source_type === props.sourceType))
const statuses: Record<ScheduleImport['status'], string> = {
  QUEUED: '等待解析', RUNNING: '正在解析', REVIEW_REQUIRED: '等待校对', FAILED: '解析失败',
  CONFIRMED: '已保存', EXPIRED: '已过期', CANCELED: '已取消',
}

function requestId(): string {
  const cryptoApi = typeof globalThis.crypto === 'undefined' ? null : globalThis.crypto
  return typeof cryptoApi?.randomUUID === 'function' ? `sch_${cryptoApi.randomUUID()}`
    : `sch_${Date.now().toString(16)}_${Math.random().toString(16).slice(2)}`
}
function clearTimer(): void { if (timer !== null) clearTimeout(timer); timer = null }
function draftKey(value: ScheduleImport): string { return `${value.import_id}:${value.version}` }
function copyEditors(value: ImportEditor[]): ImportEditor[] {
  return value.map((item) => ({ ...item, issues: [...item.issues] }))
}
function stashDraft(): void {
  if (task.value?.status === 'REVIEW_REQUIRED') localDrafts.set(draftKey(task.value), copyEditors(editors.value))
}
function clearTaskDrafts(importId: string): void {
  for (const key of localDrafts.keys()) if (key.startsWith(`${importId}:`)) localDrafts.delete(key)
}
function setTask(value: ScheduleImport, hydrate: boolean): void {
  if (hydrate) stashDraft()
  task.value = value
  recent.value = recent.value.map((item) => item.import_id === value.import_id ? value : item)
  if (hydrate) {
    const saved = value.status === 'REVIEW_REQUIRED' ? localDrafts.get(draftKey(value)) : undefined
    editors.value = saved ? copyEditors(saved) : (value.draft?.rows ?? []).map(suggestionToEditor)
    if (['CONFIRMED', 'CANCELED', 'EXPIRED'].includes(value.status)) {
      clearTaskDrafts(value.import_id)
    }
    confirmOperation.value = null
  }
}
async function loadRecent(): Promise<void> {
  recent.value = await listScheduleImports(props.term.term_id)
}
async function openExisting(id: string): Promise<void> {
  const current = ++generation
  clearTimer()
  message.value = ''
  try {
    const next = await getScheduleImport(id)
    if (current !== generation || !visible.value) return
    setTask(next, true)
    schedulePoll(current)
  } catch (error) { if (current === generation) notifyError(error) }
}
function schedulePoll(current: number): void {
  clearTimer()
  if (!task.value || !['QUEUED', 'RUNNING'].includes(task.value.status)) return
  timer = setTimeout(() => { void pollOnce(current) }, 2000)
}
async function pollOnce(current: number): Promise<void> {
    try {
      const next = await getScheduleImport(task.value!.import_id)
      if (current !== generation || !visible.value) return
      const newlyReady = next.status === 'REVIEW_REQUIRED' && task.value?.status !== 'REVIEW_REQUIRED'
      setTask(next, newlyReady)
      schedulePoll(current)
    } catch (error) {
      if (current === generation) message.value = error instanceof Error ? error.message : '状态读取失败，请手动刷新。'
    }
}
watch([() => props.modelValue, () => props.term.term_id, () => props.sourceType], async ([open, termId, sourceType]) => {
  if (termId !== lastTermId || sourceType !== lastSourceType) {
    lastTermId = termId; lastSourceType = sourceType; startNew(); recent.value = []
  }
  if (!open) { generation++; clearTimer(); return }
  const current = ++generation
  try {
    await loadRecent()
    if (current !== generation || !visible.value) return
    const candidate = visibleRecent.value.find((item) => item.import_id === task.value?.import_id)
      ?? visibleRecent.value.find((item) => ['QUEUED', 'RUNNING', 'REVIEW_REQUIRED', 'FAILED'].includes(item.status))
    if (candidate) await openExisting(candidate.import_id)
  } catch (error) { if (current === generation) notifyError(error) }
}, { immediate: true })
function clearPreview(): void {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}
watch([() => visible.value, () => task.value?.import_id, () => task.value?.source_type,
  () => !!task.value && ['QUEUED', 'RUNNING', 'REVIEW_REQUIRED', 'FAILED'].includes(task.value.status)],
async ([open, id, sourceType, live], _previous, onCleanup) => {
  const current = ++previewGeneration
  clearPreview()
  if (!open || !id || sourceType !== 'IMAGE' || !live) return
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  try {
    const blob = await getScheduleImportImage(id, controller.signal)
    if (current !== previewGeneration || controller.signal.aborted) return
    previewUrl.value = URL.createObjectURL(blob)
  } catch (error) {
    if (current === previewGeneration && !controller.signal.aborted) {
      message.value = error instanceof Error ? `原图加载失败：${error.message}` : '原图加载失败，请刷新。'
    }
  }
}, { immediate: true })
onUnmounted(() => { generation++; previewGeneration++; clearTimer(); clearPreview() })

function startNew(): void {
  generation++
  clearTimer()
  stashDraft()
  task.value = null
  editors.value = []
  rawText.value = ''
  imageFile.value = null
  imageOperation = null
  message.value = ''
  createOperation.value = null
  confirmOperation.value = null
}
function selectImage(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  imageOperation = null
  if (file && (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 10 * 1024 * 1024 || file.size === 0)) {
    imageFile.value = null
    input.value = ''
    notifyWarning('请选择不超过 10 MiB 的 PNG 或 JPEG 图片。')
    return
  }
  imageFile.value = file
}
async function imageConsent(retry: boolean): Promise<ScheduleImageModel | null> {
  const model = await getScheduleImageModel()
  const replacement = retry ? '\n再次识别会替换当前尚未保存的校对内容。' : ''
  try {
    await ElMessageBox.confirm(`图片将发送给你配置的模型服务：\n${model.provider} · ${model.model_name}\n${model.origin}\n\n请确认该模型支持图片，服务商可能按你的配置收费。未完成的原图默认保留 24 小时，确认或取消后尽快删除；取消无法撤回已发送的请求。${replacement}`,
      '确认发送课表图片', { type: 'warning', confirmButtonText: '同意发送', cancelButtonText: '暂不发送' })
    return model
  } catch { return null }
}
async function createImage(): Promise<void> {
  if (busy.value) return
  const file = imageFile.value
  if (!file) { notifyWarning('请先选择课表图片。'); return }
  const termId = props.term.term_id
  const current = generation
  busy.value = true
  try {
    const model = await imageConsent(false)
    if (!model || current !== generation || !visible.value || imageFile.value !== file || props.term.term_id !== termId) return
    if (!imageOperation || imageOperation.file !== file || imageOperation.termId !== termId
      || imageOperation.consentToken !== model.confirmation_token) {
      imageOperation = { file, termId, consentToken: model.confirmation_token, requestId: requestId() }
    }
    const value = await createImageScheduleImport(termId, file, imageOperation.requestId, model.confirmation_token)
    if (props.term.term_id !== termId || current !== generation) return
    const nextGeneration = ++generation
    setTask(value, true)
    imageFile.value = null
    imageOperation = null
    await loadRecent()
    if (visible.value) schedulePoll(nextGeneration)
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
async function create(): Promise<void> {
  if (busy.value) return
  if (!rawText.value.trim()) { notifyWarning('请先粘贴课表文字。'); return }
  const fingerprint = JSON.stringify([props.term.term_id, rawText.value])
  createOperation.value = operationFor(createOperation.value, fingerprint, requestId)
  busy.value = true
  try {
    const value = await createTextScheduleImport(props.term.term_id, rawText.value, createOperation.value.requestId)
    const current = ++generation
    setTask(value, true)
    rawText.value = ''
    createOperation.value = null
    await loadRecent()
    schedulePoll(current)
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
async function retry(): Promise<void> {
  if (!task.value || busy.value) return
  const currentTask = task.value
  const current = generation
  busy.value = true
  try {
    let next: ScheduleImport
    if (currentTask.source_type === 'IMAGE') {
      const model = await imageConsent(true)
      if (!model || current !== generation || !visible.value || task.value?.import_id !== currentTask.import_id) return
      next = await retryImageScheduleImport(currentTask.import_id, currentTask.version, model.confirmation_token)
    } else {
      next = await retryScheduleImport(currentTask.import_id, currentTask.version)
    }
    if (current !== generation) return
    const nextGeneration = ++generation
    setTask(next, true)
    schedulePoll(nextGeneration)
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
async function cancel(): Promise<void> {
  if (!task.value || busy.value) return
  const detail = task.value.source_type === 'IMAGE' ? '取消后会尽快删除临时图片和未保存的校对内容；已发送的请求无法撤回。' : '取消后会删除临时原文和未保存的校对内容。'
  try { await ElMessageBox.confirm(detail, '取消导入', {
    type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '继续校对',
  }) } catch { return }
  busy.value = true
  try {
    const next = await cancelScheduleImport(task.value.import_id, task.value.version)
    generation++
    clearTimer()
    setTask(next, true)
    await loadRecent()
    notifySuccess('导入已取消。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
function addBlank(): void {
  editors.value.push({ included: true, sourceText: '', issues: [], title: '', teacher: '', room: '', weekday: undefined,
    weekText: '', timeMode: 'time', startTime: '', endTime: '', startPeriod: undefined, endPeriod: undefined })
}
async function confirmRules(): Promise<void> {
  const current = task.value
  if (!current || busy.value) return
  let rules: RuleWrite[]
  try {
    if (missingPeriods.value.length) throw new Error(`请先设置第 ${missingPeriods.value.join('、')} 节的时间。`)
    rules = editors.value.filter((item) => item.included).map((item) => editorToRule(item, props.term))
    if (!rules.length) throw new Error('请至少选择一条课程。')
    if (rules.length > 200) throw new Error('每次最多保存 200 条课程。')
  } catch (error) { notifyWarning(error instanceof Error ? error.message : '请检查课程内容。'); return }
  const warnings = importConflicts(rules, props.rules, props.periods)
  const reviewedScheduleVersion = props.term.schedule_version
  const changed = current.schedule_version !== reviewedScheduleVersion
  const details = warnings.length ? `\n\n请注意：\n${warnings.slice(0, 10).join('\n')}${warnings.length > 10 ? '\n还有其他重叠，请检查课程。' : ''}` : ''
  const changedNotice = changed ? '\n\n课表或节次已变化，保存时将使用当前节次时间，并重新检查课程冲突。' : ''
  try { await ElMessageBox.confirm(`将追加 ${rules.length} 条已校对课程到「${props.term.name}」。${changedNotice}${details}`,
    '确认保存课表', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '继续校对' }) }
  catch { return }
  if (props.term.schedule_version !== reviewedScheduleVersion) {
    notifyWarning('课表又发生了变化，请重新检查后保存。')
    return
  }
  if (warnings.length) rules = rules.map((rule) => ({ ...rule, confirm_overlap: true }))
  busy.value = true
  const submit = async (items: RuleWrite[]): Promise<ScheduleImportReceipt> => {
    const fingerprint = JSON.stringify([current.import_id, current.version, reviewedScheduleVersion, items])
    confirmOperation.value = operationFor(confirmOperation.value, fingerprint, requestId)
    return confirmScheduleImport(current.import_id, { version: current.version,
      client_request_id: confirmOperation.value.requestId, rules: items, reviewed_schedule_version: reviewedScheduleVersion })
  }
  try {
    let receipt: ScheduleImportReceipt
    try { receipt = await submit(rules) }
    catch (error) {
      if (!(error instanceof ApiError && error.code === 'STATE_CONFLICT' && error.message.includes('重叠'))) throw error
      await ElMessageBox.confirm(error.message, '课程时间重叠', {
        type: 'warning', confirmButtonText: '仍要保存', cancelButtonText: '返回校对',
      })
      receipt = await submit(rules.map((rule) => ({ ...rule, confirm_overlap: true })))
    }
    generation++
    clearTimer()
    setTask({ ...current, status: 'CONFIRMED', receipt, source_text: null, draft: null }, true)
    confirmOperation.value = null
    emit('saved')
    notifySuccess('课表已保存。')
    try { await loadRecent() } catch { /* 已保存；历史列表可稍后刷新。 */ }
  } catch (error) { if (error !== 'cancel' && error !== 'close') notifyError(error) }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="sourceType === 'IMAGE' ? '导入图片课表' : '导入文字课表'" width="min(920px, calc(100vw - 24px))" class="import-dialog">
    <p v-if="sourceType === 'TEXT'" class="import-hint">每行一条，例如：高等数学 周一 第1-2节 1-16周 教师：张老师，教室：A101。系统只提供建议，请逐项核对。</p>
    <p v-else class="import-hint">选择 PNG 或 JPEG 课表图片。识别结果仅供参考，请对照原图逐项校对后保存。</p>
    <p v-if="periods.length === 0" class="import-warning">还没有设置节次时间。设置一次后，按节次识别的课程会共用这些时间。
      <el-button v-if="!task || !['QUEUED', 'RUNNING'].includes(task.status)" text type="primary" @click="emit('configure-periods')">设置节次时间</el-button>
      <span v-else>识别完成后即可设置。</span>
    </p>
    <div v-if="visibleRecent.length" class="import-history">
      <label for="import-history">最近的导入</label>
      <el-select id="import-history" :model-value="task?.import_id ?? ''" placeholder="选择已有任务" @change="openExisting">
        <el-option v-for="item in visibleRecent" :key="item.import_id" :value="item.import_id" :label="`任务 ${item.import_id} · ${statuses[item.status]}`" />
      </el-select>
      <el-button @click="startNew">新建导入</el-button>
    </div>
    <template v-if="!task">
      <template v-if="sourceType === 'TEXT'">
        <el-input v-model="rawText" type="textarea" :rows="8" maxlength="20000" show-word-limit placeholder="粘贴课表文字，每行一条" />
        <p class="import-hint">文字临时保存，确认或取消后清除；未完成任务会在有效期后清理。不会调用模型。</p>
        <el-button type="primary" :loading="busy" @click="create">生成校对建议</el-button>
      </template>
      <template v-else>
        <label class="image-input">选择图片 <input type="file" accept="image/png,image/jpeg" :disabled="busy" @change="selectImage" /></label>
        <p class="import-hint">图片不超过 10 MiB。识别前会显示你的模型服务和可能产生的费用，确认后才发送。</p>
        <el-button type="primary" :loading="busy" @click="createImage">继续识别</el-button>
      </template>
    </template>
    <template v-else>
      <div class="import-status"><strong>{{ statuses[task.status] }}</strong><span v-if="!['CONFIRMED', 'CANCELED', 'EXPIRED'].includes(task.status)">原文有效至 {{ deadline }}（{{ term.timezone }}）</span><el-button text @click="openExisting(task.import_id)">刷新状态</el-button></div>
      <p v-if="message" role="alert" class="import-warning">{{ message }} <el-button text @click="openExisting(task.import_id)">重试</el-button></p>
      <figure v-if="previewUrl" class="image-preview"><img :src="previewUrl" alt="待校对的课表原图" /><figcaption>原图仅供本次校对</figcaption></figure>
      <p v-if="task.status === 'QUEUED' || task.status === 'RUNNING'" class="import-hint">正在处理，完成后会显示可编辑的建议。</p>
      <div v-if="task.status === 'FAILED'" role="alert" class="import-warning">
        {{ task.error_message || '无法生成建议。' }} 可重试，或关闭窗口手动添加课程。
        <el-button :disabled="task.attempt >= 3" :loading="busy" @click="retry">重试解析</el-button>
      </div>
      <p v-if="task.status === 'CONFIRMED'" class="import-hint">已保存 {{ task.receipt?.rule_ids.length ?? 0 }} 条课程，临时内容正在清理。</p>
      <p v-if="task.status === 'EXPIRED' || task.status === 'CANCELED'" class="import-hint">临时内容已不可用，可以新建导入。</p>
      <template v-if="task.status === 'REVIEW_REQUIRED'">
        <p v-if="scheduleChanged" class="import-warning">课表或节次已变化，请按当前时间重新核对；不需要再次识别图片。</p>
        <p v-if="missingPeriods.length" class="import-warning">第 {{ missingPeriods.join('、') }} 节还没有设置时间。
          <el-button text type="primary" @click="emit('configure-periods')">设置节次时间</el-button>
        </p>
        <div v-if="task.error_message" role="alert" class="import-warning">
          {{ task.error_message }}
          <el-button v-if="task.source_type === 'IMAGE'" :disabled="task.attempt >= 3" :loading="busy" @click="retry">重新确认并识别</el-button>
          <span v-if="task.source_type === 'IMAGE' && task.attempt >= 3">本任务已达到识别次数上限；可对照原图手动填写，或新建导入。</span>
        </div>
        <p class="import-hint">已选 {{ selectedCount }} 条。可修改每一项、取消不需要的行，或添加空白行。修改暂存在当前页面，刷新或离开页面前请确认保存。</p>
        <article v-for="(row, index) in editors" :key="index" class="import-row">
          <div class="import-row__heading"><el-checkbox v-model="row.included">保存第 {{ index + 1 }} 条</el-checkbox><el-button text type="danger" @click="editors.splice(index, 1)">移除</el-button></div>
          <p v-if="row.sourceText" class="import-source">原文：{{ row.sourceText }}</p>
          <p v-if="row.issues.length" class="import-warning">{{ row.issues.join('；') }}</p>
          <div class="import-fields">
            <label>课程名称<el-input v-model="row.title" maxlength="200" /></label>
            <label>星期<el-select v-model="row.weekday" placeholder="请选择"><el-option v-for="(day, dayIndex) in weekdays" :key="day" :label="day" :value="dayIndex + 1" /></el-select></label>
            <label>教学周次<el-input v-model="row.weekText" placeholder="例如 1-16单周" /></label>
            <label>教师<el-input v-model="row.teacher" maxlength="100" /></label>
            <label>教室<el-input v-model="row.room" maxlength="200" /></label>
          </div>
          <div class="import-times">
            <el-radio-group v-model="row.timeMode" aria-label="上课时间方式"><el-radio-button value="period">按节次</el-radio-button><el-radio-button value="time">按时刻</el-radio-button></el-radio-group>
            <template v-if="row.timeMode === 'period'"><span>第</span><el-input-number v-model="row.startPeriod" :min="1" :max="30" controls-position="right" aria-label="开始节次" /><span>至</span><el-input-number v-model="row.endPeriod" :min="1" :max="30" controls-position="right" aria-label="结束节次" /><span>节</span></template>
            <template v-else><el-time-picker v-model="row.startTime" format="HH:mm" value-format="HH:mm:ss" placeholder="开始" /><span>至</span><el-time-picker v-model="row.endTime" format="HH:mm" value-format="HH:mm:ss" placeholder="结束" /></template>
          </div>
        </article>
        <el-button @click="addBlank">添加空白行</el-button>
      </template>
    </template>
    <template #footer>
      <el-button v-if="task && ['QUEUED', 'RUNNING', 'REVIEW_REQUIRED', 'FAILED'].includes(task.status)" :loading="busy" @click="cancel">取消导入</el-button>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="task?.status === 'REVIEW_REQUIRED'" type="primary" :loading="busy" @click="confirmRules">确认保存所选课程</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-hint { color: var(--sc-text-secondary); line-height: 1.6; }
.import-history, .import-status, .import-row__heading, .import-times { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.import-history { margin: 16px 0; }
.import-history .el-select { width: 240px; }
.import-status { margin: 16px 0; }
.import-status span { color: var(--sc-text-secondary); font-size: 13px; }
.import-row { border: 1px solid var(--sc-border); border-radius: 10px; padding: 14px; margin: 12px 0; }
.import-row__heading { justify-content: space-between; }
.import-source { color: var(--sc-text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.import-warning { color: var(--sc-warning); line-height: 1.5; }
.image-input { display: grid; gap: 8px; margin: 16px 0; }
.image-input input { max-width: 100%; }
.image-preview { margin: 16px 0; color: var(--sc-text-secondary); font-size: 13px; }
.image-preview img { display: block; max-width: 100%; max-height: 420px; object-fit: contain; object-position: left; margin-bottom: 6px; }
.import-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.import-fields label { display: grid; gap: 6px; min-width: 0; font-size: 13px; }
.import-times { margin-top: 14px; }
.import-times :deep(.el-input-number) { width: 110px; }
.import-times :deep(.el-date-editor) { max-width: 150px; }
@media (max-width: 720px) { .import-fields { grid-template-columns: 1fr 1fr; } .import-history .el-select { width: 100%; } }
</style>
