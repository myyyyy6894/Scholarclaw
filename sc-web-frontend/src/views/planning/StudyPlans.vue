<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { acceptStudyPlan, cancelStudyPlan, changeStudyBlock, createStudyPlan, getStudyPlan, listStudyPlans } from '@/api/planning'
import type { ChangeStudyBlock, StudyBlockAction, StudyPlan, StudyPlanBlock, StudyPlanSummary } from '@/api/planning'
import BaseCard from '@/components/ui/BaseCard.vue'
import { notifyError, notifySuccess } from '@/utils/notify'
import { requireOffsetTimestamp } from '@/views/planning/planning-time'

const props = defineProps<{ timezone: string; inputRevision: number }>()
const emit = defineEmits<{ 'progress-changed': [] }>()
const summaries = ref<StudyPlanSummary[]>([])
const current = ref<StudyPlan | null>(null)
const selectedPlanId = ref('')
const selectedBlocks = ref<string[]>([])
const fromDate = ref('')
const toDate = ref('')
const confirmNoTerm = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const moveDialog = ref(false)
const movingBlock = ref<StudyPlanBlock | null>(null)
const moveStart = ref('')
const moveEnd = ref('')
const availableBlocks = computed(() => current.value?.blocks.filter((block) => block.state !== 'CANCELED') ?? [])
type Operation = { fingerprint: string; id: string }
let createOperation: Operation | null = null
let acceptOperation: Operation | null = null

function requestId(): string {
  const cryptoApi = typeof globalThis.crypto === 'undefined' ? null : globalThis.crypto
  return typeof cryptoApi?.randomUUID === 'function' ? `plan_${cryptoApi.randomUUID()}`
    : `plan_${Date.now().toString(16)}_${Math.random().toString(16).slice(2)}`
}
function operation(previous: Operation | null, fingerprint: string): Operation {
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, id: requestId() }
}
function todayInZone(zone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date())
  const part = (key: string): string => parts.find((entry) => entry.type === key)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}
function addDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}
async function refreshList(): Promise<void> { summaries.value = await listStudyPlans() }
async function showPlan(id: string): Promise<void> {
  if (!id) { current.value = null; return }
  try {
    current.value = await getStudyPlan(id)
    selectedPlanId.value = id
    selectedBlocks.value = []
  } catch (error) { notifyError(error) }
}
onMounted(async () => {
  try {
    fromDate.value = todayInZone(props.timezone)
    toDate.value = addDays(fromDate.value, 6)
    await refreshList()
    const latest = summaries.value.find((plan) => plan.state !== 'CANCELED')
    if (latest) await showPlan(latest.plan_id)
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '计划读取失败。' }
})
watch(() => props.inputRevision, () => { if (selectedPlanId.value) void showPlan(selectedPlanId.value) })

async function generate(): Promise<void> {
  if (busy.value) return
  try {
    if (!fromDate.value || !toDate.value || fromDate.value > toDate.value) throw new Error('请选择有效的规划日期范围。')
    const fingerprint = JSON.stringify([fromDate.value, toDate.value, confirmNoTerm.value])
    createOperation = operation(createOperation, fingerprint)
    busy.value = true
    const plan = await createStudyPlan({ from_date: fromDate.value, to_date: toDate.value,
      confirm_no_active_term: confirmNoTerm.value, client_request_id: createOperation.id })
    createOperation = null
    current.value = plan
    selectedPlanId.value = plan.plan_id
    selectedBlocks.value = []
    await refreshList()
    notifySuccess('计划建议已生成，请检查后选择采纳。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
async function accept(): Promise<void> {
  const plan = current.value
  if (!plan || busy.value) return
  try {
    if (!selectedBlocks.value.length) throw new Error('请先选择需要采纳的时间块。')
    const blockIds = [...selectedBlocks.value].sort()
    const fingerprint = JSON.stringify([plan.plan_id, plan.revision, blockIds])
    acceptOperation = operation(acceptOperation, fingerprint)
    busy.value = true
    current.value = await acceptStudyPlan(plan.plan_id, plan.revision, acceptOperation.id, blockIds)
    acceptOperation = null
    selectedBlocks.value = []
    await refreshList()
    notifySuccess('已采纳所选时间块。')
  } catch (error) {
    notifyError(error)
    // 保留请求 ID 以便网络失败后重试；若已过期，刷新会展示失效状态。
    try { current.value = await getStudyPlan(plan.plan_id) } catch { /* 保留已有页面，允许再次重试 */ }
  } finally { busy.value = false }
}
async function cancelDraft(): Promise<void> {
  const plan = current.value
  if (!plan || busy.value) return
  try { await ElMessageBox.confirm('取消这份计划草稿？已采纳的计划不会受影响。', '取消草稿', { type: 'warning' }) }
  catch { return }
  busy.value = true
  try {
    await cancelStudyPlan(plan.plan_id, plan.revision)
    current.value = await getStudyPlan(plan.plan_id)
    selectedBlocks.value = []
    await refreshList()
    notifySuccess('草稿已取消。')
  } catch (error) { notifyError(error) } finally { busy.value = false }
}
function openMove(block: StudyPlanBlock): void {
  movingBlock.value = block
  moveStart.value = block.start_at
  moveEnd.value = block.end_at
  moveDialog.value = true
}
async function changeBlock(block: StudyPlanBlock, action: StudyBlockAction, startAt?: string, endAt?: string): Promise<void> {
  const plan = current.value
  if (!plan || busy.value) return
  if (action === 'COMPLETE' || action === 'CANCEL') {
    const message = action === 'COMPLETE' ? `记录「${block.title}」这段时间已完成？将按该时长扣减剩余工时，DDL 不会自动完成。`
      : `取消「${block.title}」的这段学习安排？DDL 任务和工时资料会保留。`
    try { await ElMessageBox.confirm(message, action === 'COMPLETE' ? '记录进度' : '取消安排', { type: 'warning' }) }
    catch { return }
  }
  try {
    const body: ChangeStudyBlock = { action, revision: plan.revision, version: block.version }
    if (action === 'MOVE') {
      body.start_at = requireOffsetTimestamp(startAt ?? '', '开始时间') ?? undefined
      body.end_at = requireOffsetTimestamp(endAt ?? '', '结束时间') ?? undefined
      if (!body.start_at || !body.end_at) throw new Error('请填写带时区偏移的起止时间。')
    }
    busy.value = true
    current.value = await changeStudyBlock(plan.plan_id, block.block_id, body)
    moveDialog.value = false
    if (action === 'COMPLETE') emit('progress-changed')
    await refreshList()
    notifySuccess(action === 'COMPLETE' ? '学习进度已记录。' : '学习安排已更新。')
  } catch (error) {
    notifyError(error)
    try { current.value = await getStudyPlan(plan.plan_id) } catch { /* 保留当前内容供用户重试 */ }
  } finally { busy.value = false }
}
function blockTime(block: StudyPlanBlock): string {
  const start = block.start_at.slice(0, 16).replace('T', ' ')
  const end = block.start_at.slice(0, 10) === block.end_at.slice(0, 10)
    ? block.end_at.slice(11, 16) : block.end_at.slice(0, 16).replace('T', ' ')
  return `${start}–${end}`
}
const stateLabels: Record<StudyPlanSummary['state'], string> = { DRAFT: '待采纳', ACCEPTED: '已采纳', CANCELED: '已取消' }
</script>

<template>
  <BaseCard title="学习计划" subtitle="按已保存的学习时间、课表、固定日程和剩余工时生成建议。">
    <p v-if="errorMessage" role="alert" class="plan-error">{{ errorMessage }}</p>
    <div class="plan-form">
      <label>开始日期<el-date-picker v-model="fromDate" type="date" value-format="YYYY-MM-DD" /></label>
      <label>结束日期<el-date-picker v-model="toDate" type="date" value-format="YYYY-MM-DD" /></label>
    </div>
    <el-checkbox v-model="confirmNoTerm">如果没有当前学期，我确认本次不检查课表</el-checkbox>
    <div class="plan-actions"><el-button type="primary" :loading="busy" @click="generate">生成计划建议</el-button></div>
    <p class="plan-hint">最多规划 31 天；建议不会修改 DDL。请先保存下方的学习时间和任务工时。</p>
    <label v-if="summaries.length" class="plan-select">查看已有计划
      <el-select v-model="selectedPlanId" placeholder="选择计划" @change="showPlan">
        <el-option v-for="plan in summaries" :key="plan.plan_id" :value="plan.plan_id"
          :label="`${plan.from_date} 至 ${plan.to_date} · ${stateLabels[plan.state]} #${plan.plan_id}`" />
      </el-select>
    </label>
    <section v-if="current" class="plan-result" aria-label="计划详情">
      <div class="plan-result-head"><strong>{{ current.from_date }} 至 {{ current.to_date }}</strong>
        <span>{{ stateLabels[current.state] }} · {{ current.timezone }} <el-button text :disabled="busy" @click="showPlan(current.plan_id)">刷新计划</el-button></span></div>
      <p v-if="current.stale" class="plan-warning" role="alert">相关资料或已有安排已变化，此计划已失效，请重新生成。</p>
      <p v-for="warning in current.warnings" :key="warning" class="plan-warning">{{ warning }}</p>
      <p v-for="item in current.unavailable_tasks" :key="`u-${item.task_id}`" class="plan-warning">{{ item.title }}：{{ item.reason }}</p>
      <p v-for="item in current.shortages" :key="`s-${item.task_id}`" class="plan-warning">{{ item.title }}：还缺 {{ item.missing_minutes }} 分钟。{{ item.reason }}</p>
      <p v-if="!availableBlocks.length" class="plan-hint">没有可展示的学习时间块。</p>
      <el-checkbox-group v-if="current.state === 'DRAFT' && availableBlocks.length" v-model="selectedBlocks" class="plan-blocks">
        <el-checkbox v-for="block in availableBlocks" :key="block.block_id" :value="block.block_id" :disabled="current.stale">
          <span class="plan-block"><strong>{{ block.title }}</strong><small>{{ blockTime(block) }} · {{ current.timezone }}<template v-if="block.overdue"> · 逾期补救</template></small></span>
        </el-checkbox>
      </el-checkbox-group>
      <ul v-else class="plan-blocks plan-list"><li v-for="block in availableBlocks" :key="block.block_id" class="plan-block">
        <strong>{{ block.title }}</strong><small>{{ blockTime(block) }} · {{ current.timezone }}<template v-if="block.overdue"> · 逾期补救</template> · {{ block.state === 'DONE' ? '已完成' : '已采纳' }}<template v-if="block.locked"> · 已锁定</template></small>
        <div v-if="current.state === 'ACCEPTED' && block.state === 'PLANNED'" class="plan-block-actions">
          <el-button text :disabled="busy || current.stale || block.locked" @click="openMove(block)">移动</el-button>
          <el-button text :disabled="busy" @click="changeBlock(block, block.locked ? 'UNLOCK' : 'LOCK')">{{ block.locked ? '解锁' : '锁定' }}</el-button>
          <el-button text :disabled="busy || current.stale" @click="changeBlock(block, 'COMPLETE')">标记完成</el-button>
          <el-button text type="danger" :disabled="busy" @click="changeBlock(block, 'CANCEL')">取消安排</el-button>
        </div>
      </li></ul>
      <div v-if="current.state === 'DRAFT'" class="plan-actions">
        <el-button :disabled="current.stale || !availableBlocks.length" @click="selectedBlocks = availableBlocks.map((block) => block.block_id)">全选建议</el-button>
        <el-button type="primary" :loading="busy" :disabled="current.stale || !selectedBlocks.length" @click="accept">采纳所选（{{ selectedBlocks.length }}）</el-button>
        <el-button :disabled="busy" @click="cancelDraft">取消草稿</el-button>
      </div>
    </section>
    <el-dialog v-model="moveDialog" title="移动学习时间" width="min(600px, calc(100vw - 24px))" :close-on-click-modal="false">
      <div class="plan-form">
        <label>开始时间（带时区偏移）<el-input v-model="moveStart" placeholder="2026-11-10T18:00+08:00" /></label>
        <label>结束时间（带时区偏移）<el-input v-model="moveEnd" placeholder="2026-11-10T19:00+08:00" /></label>
      </div>
      <p class="plan-hint">请保持原时长。系统会重新检查课表、固定日程、截止时间及每日上限。</p>
      <template #footer><el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="movingBlock && changeBlock(movingBlock, 'MOVE', moveStart, moveEnd)">保存移动</el-button></template>
    </el-dialog>
  </BaseCard>
</template>

<style scoped>
.plan-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 10px; }
.plan-form label, .plan-select { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
.plan-form :deep(.el-date-editor), .plan-select :deep(.el-select) { width: 100%; }
.plan-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.plan-actions :deep(.el-button + .el-button) { margin-left: 0; }
.plan-hint { color: var(--sc-text-secondary); line-height: 1.6; }
.plan-error { color: var(--sc-danger); }
.plan-warning { color: var(--sc-warning, #9a6200); line-height: 1.6; overflow-wrap: anywhere; }
.plan-result { margin-top: 18px; border-top: 1px solid var(--sc-border); padding-top: 14px; }
.plan-result-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; }
.plan-result-head span, .plan-block small { color: var(--sc-text-secondary); }
.plan-blocks { display: grid; gap: 8px; margin: 12px 0; }
.plan-blocks :deep(.el-checkbox) { min-height: 50px; height: auto; align-items: flex-start; white-space: normal; }
.plan-blocks :deep(.el-checkbox__input) { margin-top: 3px; }
.plan-block { display: grid; gap: 4px; overflow-wrap: anywhere; }
.plan-block-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.plan-block-actions :deep(.el-button + .el-button) { margin-left: 0; }
.plan-list { list-style: none; padding: 0; }
@media (max-width: 600px) { .plan-form { grid-template-columns: 1fr; } }
</style>
