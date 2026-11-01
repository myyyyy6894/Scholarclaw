<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { ElMessageBox } from 'element-plus'

import { listCourses } from '@/api/course'
import type { CourseNamespace, IndexStatus } from '@/api/course'
import { deleteDoc, listDocs, reindexDoc, retryDoc, uploadDoc } from '@/api/knowledge'
import type { KnowledgeDoc, ListableDocumentStatus } from '@/api/knowledge'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ProgressBar from '@/components/ui/ProgressBar.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import { useCourseStore } from '@/stores/course'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { indexStatusLabel } from '@/views/course/course-form'
import { courseReadiness } from '@/views/home/dashboard-data'
import {
  canDelete,
  canReindex,
  canRetry,
  documentStatusLabel,
  LatestRequest,
  documentStatusTagType,
  formatFileSize,
  hasPendingDocs,
  snapshotChunkCount,
  snapshotPageCount,
  uploadFileError,
  UPLOAD_ACCEPT,
} from '@/views/knowledge/knowledge-form'

/**
 * 课程知识库（P3，参考图 `docs/ui_picture/我的课程.png` 与基调图中的知识库页）。
 *
 * 所有文档状态、计数、诊断与操作都来自真实接口；上传成功后是 `UPLOADED`/`PARSING`，
 * 不在前端伪造 `READY`。新增拖拽上传区，仍复用同一套 `uploadDoc` 与文件校验。
 */
type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

const courseStore = useCourseStore()

const courses = ref<CourseNamespace[]>([])
const docs = ref<KnowledgeDoc[]>([])
const selectedCourseId = ref<string | null>(courseStore.selectedCourseId)
const filterStatus = ref<ListableDocumentStatus | ''>('')
const loadingCourses = ref(true)
const loadingDocs = ref(false)
const uploading = ref(false)
const dragActive = ref(false)
const actionDocId = ref<string | null>(null)
const loadError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const compact = ref(false)
const POLL_INTERVAL_MS = 4000
let pollTimer: number | null = null

const compactQuery =
  typeof window === 'undefined' ? null : window.matchMedia('(max-width: 640px)')
function syncCompact(): void {
  compact.value = compactQuery?.matches ?? false
}

const currentCourse = computed(
  () => courses.value.find((item) => item.course_id === selectedCourseId.value) ?? null,
)
const readiness = computed(() =>
  currentCourse.value === null ? null : courseReadiness(currentCourse.value),
)
const courseSummary = computed(() => {
  const course = currentCourse.value
  if (course === null) {
    return null
  }
  return {
    ready: course.ready_doc_count,
    parsing: course.parsing_doc_count,
    failed: course.failed_doc_count,
    version: course.index_version,
  }
})

const statusOptions: Array<{ value: ListableDocumentStatus | ''; label: string }> = [
  { value: '', label: '全部（含删除中）' },
  { value: 'UPLOADED', label: '待解析' },
  { value: 'PARSING', label: '解析中' },
  { value: 'READY', label: '就绪' },
  { value: 'FAILED', label: '失败' },
  { value: 'DELETING', label: '删除中' },
  { value: 'DELETED', label: '已删除' },
]

onMounted(() => {
  syncCompact()
  compactQuery?.addEventListener('change', syncCompact)
  void bootstrap()
})

onUnmounted(() => {
  compactQuery?.removeEventListener('change', syncCompact)
  stopPolling()
})

function syncPolling(): void {
  if (hasPendingDocs(docs.value)) {
    if (pollTimer === null) {
      pollTimer = window.setInterval(() => {
        void loadDocs()
        void loadCourses()
      }, POLL_INTERVAL_MS)
    }
  } else {
    stopPolling()
  }
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function bootstrap(): Promise<void> {
  await loadCourses()
  if (selectedCourseId.value !== null) {
    await loadDocs()
  }
}

async function loadCourses(): Promise<void> {
  loadingCourses.value = true
  loadError.value = ''
  try {
    courses.value = await listCourses()
    const exists = courses.value.some((item) => item.course_id === selectedCourseId.value)
    if (!exists) {
      selectedCourseId.value = courses.value[0]?.course_id ?? null
    }
    courseStore.select(selectedCourseId.value)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '课程列表读取失败。'
    notifyError(error)
  } finally {
    loadingCourses.value = false
  }
}

const docsRequests = new LatestRequest()

async function loadDocs(): Promise<void> {
  const courseId = selectedCourseId.value
  const requestSeq = docsRequests.start()
  if (courseId === null) {
    docs.value = []
    stopPolling()
    return
  }
  loadingDocs.value = true
  try {
    const result = await listDocs(
      courseId,
      filterStatus.value === '' ? undefined : filterStatus.value,
    )
    if (!docsRequests.isCurrent(requestSeq) || courseId !== selectedCourseId.value) {
      return
    }
    docs.value = result
    syncPolling()
  } catch (error) {
    if (!docsRequests.isCurrent(requestSeq) || courseId !== selectedCourseId.value) {
      return
    }
    docs.value = []
    notifyError(error)
    syncPolling()
  } finally {
    if (docsRequests.isCurrent(requestSeq)) {
      loadingDocs.value = false
    }
  }
}

function selectCourse(courseId: string): void {
  if (selectedCourseId.value === courseId) {
    return
  }
  selectedCourseId.value = courseId
  courseStore.select(courseId)
  void loadDocs()
}

function onFilterChange(): void {
  void loadDocs()
}

function chooseFile(): void {
  if (currentCourse.value === null || uploading.value) {
    return
  }
  fileInput.value?.click()
}

async function onFileSelected(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file === undefined) {
    return
  }
  await uploadFile(file)
}

async function uploadFile(file: File): Promise<void> {
  const course = currentCourse.value
  if (course === null || uploading.value) {
    return
  }
  const error = uploadFileError(file)
  if (error !== null) {
    notifyWarning(error)
    return
  }
  uploading.value = true
  try {
    const uploaded = await uploadDoc(course.course_id, file)
    notifySuccess(
      uploaded.status === 'PARSING'
        ? `《${uploaded.doc_name}》已上传并进入解析队列`
        : `《${uploaded.doc_name}》已上传，暂未入队，系统将自动补投`,
    )
    if (selectedCourseId.value === course.course_id) {
      await loadDocs()
    }
    await loadCourses()
  } catch (uploadError) {
    notifyError(uploadError)
  } finally {
    uploading.value = false
  }
}

function onDragOver(): void {
  if (currentCourse.value !== null && !uploading.value) {
    dragActive.value = true
  }
}

function onDragLeave(): void {
  dragActive.value = false
}

function onDrop(event: DragEvent): void {
  dragActive.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file !== undefined) {
    void uploadFile(file)
  }
}

async function onDelete(doc: KnowledgeDoc): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除课件《${doc.doc_name}》？文档会立即从检索集中移除并标记为删除中；向量索引会立即提交清理，物理文件由后续 GC 删除。`,
      '删除课件',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actionDocId.value = doc.doc_id
  try {
    await deleteDoc(doc.doc_id)
    notifySuccess('已标记为 DELETING；向量清理已提交，物理文件等待后续 GC')
    await loadDocs()
    await loadCourses()
  } catch (error) {
    notifyError(error)
  } finally {
    actionDocId.value = null
  }
}

async function onRetry(doc: KnowledgeDoc): Promise<void> {
  actionDocId.value = doc.doc_id
  try {
    const updated = await retryDoc(doc.doc_id)
    notifySuccess(`《${updated.doc_name}》已重新进入解析队列`)
    await loadDocs()
    await loadCourses()
  } catch (error) {
    notifyError(error)
  } finally {
    actionDocId.value = null
  }
}

async function onReindex(doc: KnowledgeDoc): Promise<void> {
  actionDocId.value = doc.doc_id
  try {
    const updated = await reindexDoc(doc.doc_id)
    notifySuccess(`《${updated.doc_name}》已重新进入向量重建队列`)
    await loadDocs()
    await loadCourses()
  } catch (error) {
    notifyError(error)
  } finally {
    actionDocId.value = null
  }
}

function formatTime(value: string | null): string {
  return value === null ? '—' : new Date(value).toLocaleString()
}

function indexTone(status: IndexStatus): 'success' | 'warning' | 'danger' {
  if (status === 'ACTIVE') {
    return 'success'
  }
  if (status === 'REBUILD_FAILED') {
    return 'danger'
  }
  return 'warning'
}

function documentTone(doc: KnowledgeDoc): Tone {
  const tone = documentStatusTagType(doc.status)
  return tone === 'info' ? 'neutral' : tone
}
</script>

<template>
  <div class="knowledge-page">
    <section class="knowledge-hero">
      <div class="knowledge-hero__copy">
        <span class="knowledge-hero__eyebrow">COURSE KNOWLEDGE</span>
        <h2>{{ currentCourse?.course_name ?? '课程知识库' }}</h2>
        <p>
          左侧选中的课程会作为问答的默认课程。上传成功后文件进入解析队列，解析与向量索引完成后状态变为“就绪”；
          失败文档可重试，就绪文档可重建索引。
        </p>
      </div>

      <div v-if="courseSummary !== null" class="knowledge-hero__metrics">
        <div class="knowledge-metric">
          <span>就绪</span>
          <strong>{{ courseSummary.ready }}</strong>
        </div>
        <div class="knowledge-metric">
          <span>解析中</span>
          <strong>{{ courseSummary.parsing }}</strong>
        </div>
        <div class="knowledge-metric" :class="{ 'knowledge-metric--danger': courseSummary.failed > 0 }">
          <span>失败</span>
          <strong>{{ courseSummary.failed }}</strong>
        </div>
        <div class="knowledge-metric">
          <span>索引版本</span>
          <strong>v{{ courseSummary.version }}</strong>
        </div>
      </div>
      <div v-else class="knowledge-hero__art">
        <img src="/illustrations/feature-knowledge.png" alt="" />
      </div>
    </section>

    <div class="knowledge-layout">
      <aside class="knowledge-rail">
        <BaseCard title="课程空间">
          <template #actions>
            <router-link class="card-link" :to="{ name: 'courses' }">管理课程</router-link>
          </template>

          <div v-if="loadingCourses" class="rail-skeleton" aria-hidden="true">
            <div v-for="index in 3" :key="index" class="rail-skeleton__row" />
          </div>

          <div v-else-if="loadError !== ''" class="module-error">
            <p>{{ loadError }}</p>
            <el-button size="small" @click="loadCourses">重新加载</el-button>
          </div>

          <EmptyState
            v-else-if="courses.length === 0"
            image="/illustrations/scene-study-desk.png"
            title="还没有课程空间"
            description="先创建课程，再上传课件。"
            compact
          >
            <template #action>
              <router-link class="card-link" :to="{ name: 'courses' }">去创建课程</router-link>
            </template>
          </EmptyState>

          <div v-else class="rail-courses">
            <button
              v-for="item in courses"
              :key="item.course_id"
              type="button"
              class="rail-course"
              :class="{ 'rail-course--active': item.course_id === selectedCourseId }"
              @click="selectCourse(item.course_id)"
            >
              <span class="rail-course__top">
                <strong>{{ item.course_name }}</strong>
                <TagBadge
                  :label="indexStatusLabel(item.index_status)"
                  :tone="indexTone(item.index_status)"
                />
              </span>
              <span class="rail-course__meta">
                {{ item.embedding_model }} · {{ item.embedding_dim }} 维
              </span>
              <span class="rail-course__counts">
                就绪 {{ item.ready_doc_count }} · 解析中 {{ item.parsing_doc_count }} · 失败
                {{ item.failed_doc_count }}
              </span>
            </button>
          </div>
        </BaseCard>
      </aside>

      <section class="knowledge-main">
        <BaseCard padding="none">
          <template #header>
            <div class="knowledge-header">
              <div class="knowledge-header__title">
                <span>{{ currentCourse?.course_name ?? '未选择课程' }}</span>
                <TagBadge
                  v-if="currentCourse !== null"
                  :label="indexStatusLabel(currentCourse.index_status)"
                  :tone="indexTone(currentCourse.index_status)"
                />
              </div>
              <div class="knowledge-header__actions">
                <el-select
                  v-model="filterStatus"
                  size="small"
                  class="knowledge-filter"
                  placeholder="全部（含删除中）"
                  @change="onFilterChange"
                >
                  <el-option
                    v-for="item in statusOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
                <el-button size="small" :disabled="loadingDocs" @click="loadDocs">刷新</el-button>
                <el-button
                  type="primary"
                  size="small"
                  :loading="uploading"
                  :disabled="currentCourse === null"
                  @click="chooseFile"
                >
                  上传课件
                </el-button>
              </div>
            </div>
          </template>

          <div class="knowledge-body">
            <EmptyState
              v-if="currentCourse === null"
              image="/illustrations/scene-study-desk.png"
              title="请先选择课程"
              description="在左侧选择或创建课程后，即可上传与管理课件。"
            >
              <template #action>
                <router-link class="card-link" :to="{ name: 'courses' }">去课程空间</router-link>
              </template>
            </EmptyState>

            <template v-else>
              <div class="knowledge-summary">
                <ProgressBar
                  :value="readiness?.percent ?? null"
                  label="资料就绪度"
                  :tone="readiness?.percent === 100 ? 'success' : 'primary'"
                />
                <div class="knowledge-summary__counts">
                  <span>就绪 {{ readiness?.ready ?? 0 }}</span>
                  <span>文档总数 {{ readiness?.total ?? 0 }}</span>
                </div>
              </div>

              <div
                class="upload-zone"
                :class="{
                  'upload-zone--active': dragActive,
                  'upload-zone--disabled': currentCourse === null || uploading,
                }"
                role="button"
                tabindex="0"
                aria-label="上传课件"
                @click="chooseFile"
                @keydown.enter.prevent="chooseFile"
                @keydown.space.prevent="chooseFile"
                @dragover.prevent="onDragOver"
                @dragleave.prevent="onDragLeave"
                @drop.prevent="onDrop"
              >
                <span class="upload-zone__art">
                  <img src="/illustrations/status-loading.png" alt="" loading="lazy" />
                </span>
                <span class="upload-zone__text">
                  <strong>{{ uploading ? '正在上传…' : '拖拽课件到此处，或点击上传' }}</strong>
                  <small>支持 PDF / MD / TXT；扩展名、大小与编码由服务端再次校验</small>
                </span>
                <el-button
                  type="primary"
                  plain
                  size="small"
                  :loading="uploading"
                  :disabled="currentCourse === null"
                  @click.stop="chooseFile"
                >
                  选择文件
                </el-button>
              </div>

              <input
                ref="fileInput"
                type="file"
                :accept="UPLOAD_ACCEPT"
                hidden
                @change="onFileSelected"
              />

              <div class="docs-header">
                <span>课件列表</span>
                <span class="docs-header__count">{{ docs.length }}</span>
              </div>

              <el-skeleton v-if="loadingDocs" :rows="4" animated />
              <EmptyState
                v-else-if="docs.length === 0"
                image="/illustrations/scene-study-desk.png"
                title="当前课程还没有课件"
                description="上传 PDF / MD / TXT 后，系统会解析、分块并建立向量索引。"
                compact
              >
                <template #action>
                  <el-button type="primary" size="small" @click="chooseFile">上传课件</el-button>
                </template>
              </EmptyState>
              <el-table v-else :data="docs" row-key="doc_id" size="small">
                <el-table-column label="课件" :min-width="compact ? 120 : 220">
                  <template #default="{ row }">
                    <div>{{ row.doc_name }}</div>
                    <div class="knowledge-muted">
                      {{ row.file_ext }} · {{ formatFileSize(row.file_size_bytes) }}
                      <template v-if="!compact">
                        · 解析 v{{ row.parse_version }} · 课程 v{{ row.course_index_version }}
                      </template>
                    </div>
                    <div v-if="compact && row.error_code !== null" class="knowledge-error">
                      {{ row.error_code }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="状态" :width="compact ? 80 : 90">
                  <template #default="{ row }">
                    <TagBadge
                      :label="documentStatusLabel(row.status)"
                      :tone="documentTone(row)"
                    />
                  </template>
                </el-table-column>
                <el-table-column v-if="!compact" label="页/块" width="80">
                  <template #default="{ row }">
                    {{ snapshotPageCount(row) }} / {{ snapshotChunkCount(row) }}
                  </template>
                </el-table-column>
                <el-table-column v-if="!compact" label="更新时间" width="160">
                  <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
                </el-table-column>
                <el-table-column v-if="!compact" label="诊断" min-width="140">
                  <template #default="{ row }">
                    <el-tooltip
                      v-if="row.error_msg !== null"
                      :content="row.error_msg"
                      placement="top"
                    >
                      <span class="knowledge-error">{{ row.error_code ?? 'FAILED' }}</span>
                    </el-tooltip>
                    <span v-else class="knowledge-muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" :width="compact ? 150 : 190" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="canRetry(row)"
                      text
                      type="primary"
                      :loading="actionDocId === row.doc_id"
                      @click="onRetry(row)"
                    >
                      重试
                    </el-button>
                    <el-button
                      v-if="canReindex(row)"
                      text
                      type="primary"
                      :loading="actionDocId === row.doc_id"
                      @click="onReindex(row)"
                    >
                      重建
                    </el-button>
                    <el-button
                      v-if="canDelete(row)"
                      text
                      type="danger"
                      :loading="actionDocId === row.doc_id"
                      @click="onDelete(row)"
                    >
                      删除
                    </el-button>
                    <span v-else class="knowledge-muted">
                      {{ row.status === 'DELETING' ? '清理中' : '已删除' }}
                    </span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </div>
        </BaseCard>
      </section>
    </div>
  </div>
</template>

<style scoped>
.knowledge-page {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
}

.knowledge-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 28px;
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

.knowledge-hero__copy {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 720px;
  min-width: 0;
}

.knowledge-hero__eyebrow {
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
  font-weight: 700;
  letter-spacing: 1.2px;
}

.knowledge-hero h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
}

.knowledge-hero p {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.knowledge-hero__metrics {
  display: grid;
  flex: none;
  grid-template-columns: repeat(4, minmax(64px, 1fr));
  gap: 8px;
}

.knowledge-metric {
  min-width: 64px;
  padding: 8px 10px;
  border: 1px solid var(--sc-border);
  border-radius: 10px;
  background: var(--sc-surface);
  text-align: center;
}

.knowledge-metric span {
  display: block;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.knowledge-metric strong {
  display: block;
  margin-top: 2px;
  color: var(--sc-text);
  font-size: 18px;
  font-weight: 600;
}

.knowledge-metric--danger strong {
  color: var(--sc-danger);
}

.knowledge-hero__art {
  display: grid;
  flex: none;
  place-items: center;
  width: 100px;
  height: 88px;
  padding: 6px;
  border-radius: 14px;
  background: #f1f6ff;
}

.knowledge-hero__art img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.knowledge-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: var(--sc-card-gap);
  align-items: start;
}

.knowledge-rail {
  min-width: 0;
}

.knowledge-main {
  min-width: 0;
}

.knowledge-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.knowledge-header__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--sc-text);
  font-size: var(--sc-font-body);
  font-weight: 600;
}

.knowledge-header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.knowledge-filter {
  width: 152px;
}

.knowledge-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px var(--sc-card-pad) var(--sc-card-pad);
}

.knowledge-summary {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface-muted);
}

.knowledge-summary > :first-child {
  flex: 1;
}

.knowledge-summary__counts {
  display: flex;
  flex: none;
  gap: 12px;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  white-space: nowrap;
}

.upload-zone {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px dashed var(--sc-border-strong);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface-muted);
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.upload-zone:hover,
.upload-zone:focus-visible,
.upload-zone--active {
  border-color: var(--sc-primary);
  background: var(--sc-primary-soft);
  outline: none;
}

.upload-zone--disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.upload-zone__art {
  display: grid;
  flex: none;
  place-items: center;
  width: 54px;
  height: 54px;
  padding: 5px;
  border-radius: 11px;
  background: #f1f6ff;
}

.upload-zone__art img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.upload-zone__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.upload-zone__text strong {
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.upload-zone__text small {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.5;
}

.docs-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sc-text);
  font-size: var(--sc-font-caption);
  font-weight: 600;
}

.docs-header__count {
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--sc-radius-pill);
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  font-size: var(--sc-font-hint);
  line-height: 18px;
  text-align: center;
}

.rail-courses {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rail-course {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 10px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  color: inherit;
  background: transparent;
  font-family: var(--sc-font-sans);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.rail-course:hover {
  background: var(--sc-surface-hover);
}

.rail-course--active {
  border-color: var(--sc-primary);
  background: var(--sc-active);
}

.rail-course__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.rail-course__top strong {
  overflow: hidden;
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-course__meta {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.rail-course__counts {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-hint);
}

.rail-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rail-skeleton__row {
  height: 54px;
  border-radius: 10px;
  background: var(--sc-surface-muted);
}

.knowledge-muted {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
}

.knowledge-error {
  color: var(--sc-danger);
  cursor: help;
  font-size: var(--sc-font-hint);
}

.card-link {
  color: var(--sc-primary);
  font-size: var(--sc-font-caption);
  text-decoration: none;
}

.card-link:hover {
  color: var(--sc-primary-strong);
}

.module-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

@media (max-width: 1100px) {
  .knowledge-layout {
    grid-template-columns: 1fr;
  }

  .rail-courses {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .knowledge-hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px;
  }

  .knowledge-hero__metrics {
    width: 100%;
  }

  .knowledge-hero__art {
    display: none;
  }

  .rail-courses {
    display: flex;
    flex-direction: row;
    gap: 8px;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 4px;
  }

  .rail-course {
    flex: 0 0 220px;
  }
}

@media (max-width: 639px) {
  .knowledge-summary {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }

  .knowledge-summary__counts {
    justify-content: space-between;
  }

  .upload-zone {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .upload-zone__text {
    flex-basis: calc(100% - 68px);
  }

  .upload-zone > :last-child {
    margin-left: 68px;
  }

  .knowledge-hero__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
