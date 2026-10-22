<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { ElMessageBox } from 'element-plus'

import { listCourses } from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { deleteDoc, listDocs, uploadDoc } from '@/api/knowledge'
import type { KnowledgeDoc, ListableDocumentStatus } from '@/api/knowledge'
import { useCourseStore } from '@/stores/course'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { indexStatusLabel } from '@/views/course/course-form'
import {
  canDelete,
  documentStatusLabel,
  LatestRequest,
  documentStatusTagType,
  formatFileSize,
  snapshotChunkCount,
  snapshotPageCount,
  snapshotReadyAt,
  uploadFileError,
  UPLOAD_ACCEPT,
} from '@/views/knowledge/knowledge-form'

/**
 * 课程知识库管理视图（`06` TASK-404、`04` §4.3/§4.4）。
 *
 * 左侧课程切换、右侧文档列表/上传/状态与操作入口。只展示后端真实状态：
 * 上传成功后是 `UPLOADED`，不会在页面上伪造 `READY`；异步解析由 TASK-501 接入。
 */

const courseStore = useCourseStore()

const courses = ref<CourseNamespace[]>([])
const docs = ref<KnowledgeDoc[]>([])
const selectedCourseId = ref<string | null>(courseStore.selectedCourseId)
const filterStatus = ref<ListableDocumentStatus | ''>('')
const loadingCourses = ref(true)
const loadingDocs = ref(false)
const uploading = ref(false)
const actionDocId = ref<string | null>(null)
const loadError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const compact = ref(false)

const compactQuery =
  typeof window === 'undefined' ? null : window.matchMedia('(max-width: 640px)')
function syncCompact(): void {
  compact.value = compactQuery?.matches ?? false
}

const currentCourse = computed(
  () => courses.value.find((item) => item.course_id === selectedCourseId.value) ?? null,
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
})

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
    return
  }
  loadingDocs.value = true
  try {
    const result = await listDocs(
      courseId,
      filterStatus.value === '' ? undefined : filterStatus.value,
    )
    // 快速切换课程时，过期响应不得覆盖当前课程列表
    if (!docsRequests.isCurrent(requestSeq) || courseId !== selectedCourseId.value) {
      return
    }
    docs.value = result
  } catch (error) {
    if (!docsRequests.isCurrent(requestSeq) || courseId !== selectedCourseId.value) {
      return
    }
    docs.value = []
    notifyError(error)
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
  const course = currentCourse.value
  if (file === undefined || course === null) {
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
    // 上传期间用户可能切到别的课程：只刷新上传目标课程，避免污染当前列表
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

async function onDelete(doc: KnowledgeDoc): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除课件《${doc.doc_name}》？文档会立即从检索集中移除并标记为删除中；物理文件与向量清理由后续 GC 任务完成。`,
      '删除课件',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actionDocId.value = doc.doc_id
  try {
    await deleteDoc(doc.doc_id)
    notifySuccess('已标记为 DELETING，物理文件与向量等待 TASK-901/902 GC 清理')
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
</script>

<template>
  <div>
    <section class="sc-kb-hero">
      <div class="sc-kb-hero__copy">
        <span class="sc-kb-hero__eyebrow">COURSE KNOWLEDGE</span>
        <h2>{{ currentCourse?.course_name ?? '课程知识库' }}</h2>
        <p>
          左侧选中的课程会作为后续 RAG 问答的默认课程。上传成功后文件先进入“待解析”；
          上传成功后立即发布解析任务；实际解析与索引由 TASK-502 消费者接入后推进。手动重试/重建入口待 TASK-506 联调后开放。
        </p>
      </div>
      <div v-if="courseSummary !== null" class="sc-kb-metrics">
        <div class="sc-kb-metric">
          <span>就绪</span>
          <strong>{{ courseSummary.ready }}</strong>
        </div>
        <div class="sc-kb-metric">
          <span>解析中</span>
          <strong>{{ courseSummary.parsing }}</strong>
        </div>
        <div class="sc-kb-metric sc-kb-metric--danger">
          <span>失败</span>
          <strong>{{ courseSummary.failed }}</strong>
        </div>
        <div class="sc-kb-metric">
          <span>索引版本</span>
          <strong>v{{ courseSummary.version }}</strong>
        </div>
      </div>
    </section>

    <el-row :gutter="16">
      <el-col :xs="24" :md="7">
        <el-card class="sc-card-gap" shadow="never">
          <template #header>
            <div class="sc-knowledge__header">
              <span>课程空间</span>
              <router-link :to="{ name: 'courses' }">
                <el-button text type="primary">管理课程</el-button>
              </router-link>
            </div>
          </template>

          <el-skeleton v-if="loadingCourses" :rows="3" animated />
          <el-result
            v-else-if="loadError !== ''"
            icon="error"
            title="课程读取失败"
            :sub-title="loadError"
          >
            <template #extra>
              <el-button type="primary" @click="loadCourses">重新加载</el-button>
            </template>
          </el-result>
          <el-empty v-else-if="courses.length === 0" description="还没有课程空间">
            <template #image>
              <svg class="sc-empty-art" viewBox="0 0 160 120" aria-hidden="true">
                <circle cx="122" cy="28" r="16" fill="#ffe3a3" />
                <path d="M8 96 50 58l28 24 22-18 52 32v12H8z" fill="#dbe8ff" />
                <path d="M8 106 44 82l26 14 24-10 58 20v6H8z" fill="#b9d2ff" />
                <rect x="58" y="70" width="44" height="10" rx="5" fill="#4f8cff" />
              </svg>
            </template>
            <router-link :to="{ name: 'courses' }">
              <el-button type="primary">去创建课程</el-button>
            </router-link>
          </el-empty>
          <div v-else class="sc-knowledge__courses">
            <button
              v-for="item in courses"
              :key="item.course_id"
              type="button"
              class="sc-knowledge__course"
              :class="{ 'sc-knowledge__course--active': item.course_id === selectedCourseId }"
              @click="selectCourse(item.course_id)"
            >
              <div class="sc-knowledge__course-name">
                {{ item.course_name }}
                <el-tag v-if="item.is_default" size="small" type="info">默认</el-tag>
              </div>
              <div class="sc-knowledge__muted">
                {{ item.embedding_model }} · {{ item.embedding_dim }} 维
                <span class="sc-knowledge__course-status">
                  {{ indexStatusLabel(item.index_status) }}
                </span>
              </div>
              <div class="sc-knowledge__muted">
                就绪 {{ item.ready_doc_count }} · 解析中 {{ item.parsing_doc_count }} · 失败
                {{ item.failed_doc_count }}
              </div>
            </button>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="17">
        <el-card class="sc-card-gap" shadow="never">
          <template #header>
            <div class="sc-knowledge__header">
              <div>
                <span>{{ currentCourse?.course_name ?? '未选择课程' }}</span>
                <el-tag
                  v-if="currentCourse !== null"
                  class="sc-knowledge__tag"
                  size="small"
                  :type="
                    currentCourse.index_status === 'ACTIVE'
                      ? 'success'
                      : currentCourse.index_status === 'REBUILD_FAILED'
                        ? 'danger'
                        : 'warning'
                  "
                >
                  {{ indexStatusLabel(currentCourse.index_status) }}
                </el-tag>
              </div>
              <div class="sc-knowledge__actions">
                <el-select
                  v-model="filterStatus"
                  size="small"
                  class="sc-knowledge__filter"
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
                <input
                  ref="fileInput"
                  type="file"
                  :accept="UPLOAD_ACCEPT"
                  hidden
                  @change="onFileSelected"
                />
              </div>
            </div>
          </template>

          <el-empty v-if="currentCourse === null" description="请先在左侧选择或创建课程。" />
          <template v-else>
            <el-descriptions :column="compact ? 1 : 3" border size="small" class="sc-knowledge__meta">
              <el-descriptions-item label="Embedding 模型">
                {{ currentCourse.embedding_model }}
              </el-descriptions-item>
              <el-descriptions-item label="维度">
                {{ currentCourse.embedding_dim }}
              </el-descriptions-item>
              <el-descriptions-item label="索引版本">
                v{{ currentCourse.index_version }}
              </el-descriptions-item>
            </el-descriptions>

            <el-skeleton v-if="loadingDocs" :rows="4" animated />
            <el-empty v-else-if="docs.length === 0" description="当前课程还没有课件，上传 PDF/MD/TXT 开始。">
              <template #image>
                <svg class="sc-empty-art" viewBox="0 0 160 120" aria-hidden="true">
                  <circle cx="124" cy="26" r="15" fill="#ffe3a3" />
                  <path d="M18 100V36l34-14 34 14v64H18z" fill="#e4edff" />
                  <path d="M86 100V46l30-12 26 12v54z" fill="#cfdfff" />
                  <rect x="30" y="52" width="42" height="7" rx="3.5" fill="#4f8cff" />
                  <rect x="30" y="66" width="34" height="6" rx="3" fill="#9fbbf5" />
                </svg>
              </template>
            </el-empty>
            <el-table v-else :data="docs" row-key="doc_id" size="small">
              <el-table-column label="课件" :min-width="compact ? 120 : 200">
                <template #default="{ row }">
                  <div>{{ row.doc_name }}</div>
                  <div class="sc-knowledge__muted">
                    {{ row.file_ext }} · {{ formatFileSize(row.file_size_bytes) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" :width="compact ? 80 : 100">
                <template #default="{ row }">
                  <el-tag size="small" :type="documentStatusTagType(row.status)">
                    {{ documentStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="!compact" label="版本" width="140">
                <template #default="{ row }">
                  <div>解析 v{{ row.parse_version }}</div>
                  <div class="sc-knowledge__muted">课程 v{{ row.course_index_version }}</div>
                </template>
              </el-table-column>
              <el-table-column v-if="!compact" label="页/块" width="90">
                <template #default="{ row }">
                  {{ snapshotPageCount(row) }} / {{ snapshotChunkCount(row) }}
                </template>
              </el-table-column>
              <el-table-column v-if="!compact" label="就绪时间" width="170">
                <template #default="{ row }">{{ formatTime(snapshotReadyAt(row)) }}</template>
              </el-table-column>
              <el-table-column v-if="!compact" label="更新时间" width="170">
                <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
              </el-table-column>
              <el-table-column label="诊断" min-width="160">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="row.error_msg !== null"
                    :content="row.error_msg"
                    placement="top"
                  >
                    <span class="sc-knowledge__error">{{ row.error_code ?? 'FAILED' }}</span>
                  </el-tooltip>
                  <span v-else class="sc-knowledge__muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canDelete(row)"
                    text
                    type="danger"
                    :loading="actionDocId === row.doc_id"
                    @click="onDelete(row)"
                  >
                    删除
                  </el-button>
                  <span v-else class="sc-knowledge__muted">
                    {{ row.status === 'DELETING' ? '清理中' : '已删除' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.sc-kb-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: var(--sc-page-gap);
  padding: 26px 30px;
  border-radius: var(--sc-radius);
  background: linear-gradient(120deg, #eaf2ff 0%, #f7faff 50%, #eef3ff 100%);
  box-shadow: var(--sc-shadow-soft);
}

.sc-kb-hero__copy {
  max-width: 720px;
}

.sc-kb-hero__eyebrow {
  color: var(--sc-brand-strong);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1.4px;
}

.sc-kb-hero h2 {
  margin: 8px 0 6px;
  color: #16233d;
  font-size: 24px;
  font-weight: 650;
}

.sc-kb-hero p {
  color: var(--sc-muted);
  font-size: 13px;
  line-height: 1.7;
}

.sc-kb-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(64px, 1fr));
  gap: 10px;
  flex: none;
}

.sc-kb-metric {
  min-width: 72px;
  padding: 10px 12px;
  border: 1px solid rgba(79, 140, 255, 0.18);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.78);
  text-align: center;
}

.sc-kb-metric span {
  display: block;
  color: var(--sc-muted);
  font-size: 11px;
}

.sc-kb-metric strong {
  display: block;
  margin-top: 4px;
  color: var(--sc-text);
  font-size: 20px;
  font-weight: 650;
}

.sc-kb-metric--danger strong {
  color: var(--sc-danger);
}

.sc-knowledge__course-status {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  color: var(--sc-brand-strong);
  background: var(--sc-brand-soft);
  font-size: 11px;
}

.sc-empty-art {
  width: 150px;
  height: auto;
}

.sc-knowledge__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.sc-knowledge__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sc-knowledge__filter {
  width: 120px;
}

.sc-knowledge__tag {
  margin-left: 8px;
}

.sc-knowledge__courses {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-knowledge__course {
  width: 100%;
  padding: 12px 14px;
  text-align: left;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
}

.sc-knowledge__course:hover {
  border-color: #a0cfff;
}

.sc-knowledge__course--active {
  background: linear-gradient(120deg, #eaf2ff, #f7faff);
  border-color: var(--sc-brand);
  box-shadow: 0 6px 16px rgba(79, 140, 255, 0.16);
}

.sc-knowledge__course-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.sc-knowledge__muted {
  color: #909399;
  font-size: 12px;
}

.sc-knowledge__meta {
  margin-bottom: 16px;
}

.sc-knowledge__error {
  color: #f56c6c;
  cursor: help;
}

@media (max-width: 900px) {
  .sc-kb-hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px;
  }

  .sc-kb-metrics {
    width: 100%;
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .sc-kb-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>