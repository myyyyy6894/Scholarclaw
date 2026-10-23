<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessageBox } from 'element-plus'

import {
  changeCourseEmbedding,
  createCourse,
  deleteCourse,
  listCourses,
} from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import type { EmbeddingProvider } from '@/api/user-config'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import {
  CourseFormValidationError,
  indexStatusLabel,
  toChangeEmbeddingRequest,
  toCreateCourseRequest,
} from '@/views/course/course-form'

/**
 * 课程空间管理（`06` TASK-401、`04` §4.3）。
 *
 * 只展示后端真实返回的课程、Embedding 绑定与索引状态；不伪造文档计数或维度。
 * 文档上传/解析状态属于 TASK-404，本页只显示聚合计数。
 */

const EMBEDDING_PROVIDERS: Array<{ value: EmbeddingProvider; label: string }> = [
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'ZHIPU', label: '智谱 GLM' },
  { value: 'OLLAMA', label: 'Ollama（本地/自建）' },
]

const loading = ref(true)
const loadError = ref('')
const courses = ref<CourseNamespace[]>([])
const creating = ref(false)
const switching = ref(false)
const deletingId = ref<string | null>(null)

interface SwitchFormState {
  course: CourseNamespace | null
  useUserDefault: boolean
  provider: EmbeddingProvider
  model: string
}

const createForm = ref({ name: '', description: '' })
const switchForm = ref<SwitchFormState>({
  course: null,
  useUserDefault: true,
  provider: 'OPENAI_COMPATIBLE',
  model: '',
})

const hasCourses = computed(() => courses.value.length > 0)
const summary = computed(() => ({
  courses: courses.value.length,
  ready: courses.value.reduce((total, item) => total + item.ready_doc_count, 0),
  parsing: courses.value.reduce((total, item) => total + item.parsing_doc_count, 0),
  failed: courses.value.reduce((total, item) => total + item.failed_doc_count, 0),
}))

onMounted(() => {
  void loadCourses()
})

async function loadCourses(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    courses.value = await listCourses()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '课程列表读取失败。'
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createForm.value = { name: '', description: '' }
  creating.value = true
}

async function submitCreate(): Promise<void> {
  if (!creating.value) {
    return
  }
  try {
    const request = toCreateCourseRequest(createForm.value.name, createForm.value.description)
    creating.value = false
    const created = await createCourse(request)
    notifySuccess(`课程空间「${created.course_name}」创建成功`)
    await loadCourses()
  } catch (error) {
    if (error instanceof CourseFormValidationError) {
      notifyWarning(error.message)
    } else {
      notifyError(error)
    }
  }
}

function openSwitch(course: CourseNamespace): void {
  switchForm.value = {
    course,
    useUserDefault: true,
    provider: course.embedding_provider,
    model: '',
  }
}

async function submitSwitch(): Promise<void> {
  const course = switchForm.value.course
  if (course === null) {
    return
  }
  if (switching.value) {
    return
  }
  try {
    const request = toChangeEmbeddingRequest(
      switchForm.value.useUserDefault,
      switchForm.value.provider,
      switchForm.value.model,
    )
    switching.value = true
    const result = await changeCourseEmbedding(course.course_id, request)
    switchForm.value.course = null
    notifySuccess(
      `已切换到 ${result.embedding_model}（维度 ${result.embedding_dim}，索引版本 ${result.current_index_version}）；已提交重建请求，重建期间文档暂停检索`,
    )
    await loadCourses()
  } catch (error) {
    if (error instanceof CourseFormValidationError) {
      notifyWarning(error.message)
    } else {
      notifyError(error)
    }
  } finally {
    switching.value = false
  }
}

async function onDelete(course: CourseNamespace): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除课程空间「${course.course_name}」？关联的 DDL 待办任务会解绑保留。`,
      '删除课程',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingId.value = course.course_id
  try {
    await deleteCourse(course.course_id)
    notifySuccess('课程知识库已删除，关联的 DDL 待办任务已解绑保留')
    await loadCourses()
  } catch (error) {
    notifyError(error)
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <div>
    <section class="sc-hero">
      <div class="sc-hero__copy">
        <span class="sc-hero__eyebrow">COURSE WORKSPACE</span>
        <h2>把每门课的知识，整理成可检索的脉络</h2>
        <p>每门课程锁定一套 Embedding 模型与维度；索引版本、文档状态都来自后端真实数据。</p>
      </div>
      <div class="sc-hero__art" aria-hidden="true">
        <svg viewBox="0 0 240 120" role="presentation">
          <defs>
            <linearGradient id="sc-hero-sky" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#ffffff" stop-opacity="0.5" />
              <stop offset="100%" stop-color="#ffffff" stop-opacity="0" />
            </linearGradient>
          </defs>
          <rect width="240" height="120" fill="url(#sc-hero-sky)" />
          <circle cx="190" cy="30" r="14" fill="#ffd98a" />
          <path d="M0 96 52 52l34 30 26-22 44 36 30-20 54 30v14H0z" fill="#ffffff" opacity="0.6" />
          <path d="M0 108 46 78l32 18 30-12 40 24 34-14 58 22v4H0z" fill="#ffffff" opacity="0.85" />
          <rect x="84" y="76" width="54" height="8" rx="4" fill="#4f8cff" opacity="0.7" />
          <rect x="90" y="88" width="42" height="6" rx="3" fill="#7b5cff" opacity="0.5" />
        </svg>
      </div>
    </section>

    <section class="sc-stats">
      <article class="sc-stat">
        <span class="sc-stat__label">课程空间</span>
        <strong>{{ summary.courses }}</strong>
        <span class="sc-stat__hint">当前账号活动课程</span>
      </article>
      <article class="sc-stat">
        <span class="sc-stat__label">就绪文档</span>
        <strong>{{ summary.ready }}</strong>
        <span class="sc-stat__hint">版本匹配的可用课件</span>
      </article>
      <article class="sc-stat">
        <span class="sc-stat__label">解析中</span>
        <strong>{{ summary.parsing }}</strong>
        <span class="sc-stat__hint">待解析 + 解析中</span>
      </article>
      <article class="sc-stat sc-stat--danger">
        <span class="sc-stat__label">失败文档</span>
        <strong>{{ summary.failed }}</strong>
        <span class="sc-stat__hint">需在后续任务中重试</span>
      </article>
    </section>

    <el-card class="sc-card-gap" shadow="never">
      <template #header>
        <div class="sc-course__header">
          <span>课程空间</span>
          <el-button type="primary" @click="openCreate">新建课程</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="每门课程锁定一套 Embedding 模型与维度"
        description="创建时会复制【个人设置】中已验证的默认 Embedding 配置；切换模型会重新探测并升级课程索引版本，并异步重建该课程全部文档的向量索引。"
      />

      <el-skeleton v-if="loading" class="sc-course__skeleton" :rows="4" animated />
      <el-result
        v-else-if="loadError !== ''"
        icon="error"
        title="课程列表读取失败"
        :sub-title="loadError"
      >
        <template #extra>
          <el-button type="primary" @click="loadCourses">重新加载</el-button>
        </template>
      </el-result>
      <el-empty v-else-if="!hasCourses" description="还没有课程空间，先新建一门课程。">
        <template #image>
          <svg class="sc-empty-art" viewBox="0 0 160 120" aria-hidden="true">
            <circle cx="122" cy="28" r="16" fill="#ffe3a3" />
            <path d="M8 96 50 58l28 24 22-18 52 32v12H8z" fill="#dbe8ff" />
            <path d="M8 106 44 82l26 14 24-10 58 20v6H8z" fill="#b9d2ff" />
            <rect x="58" y="70" width="44" height="10" rx="5" fill="#4f8cff" />
          </svg>
        </template>
        <el-button type="primary" @click="openCreate">新建课程</el-button>
      </el-empty>
      <el-table v-else :data="courses" row-key="course_id">
        <el-table-column prop="course_name" label="课程" min-width="160">
          <template #default="{ row }">
            <span>{{ row.course_name }}</span>
            <el-tag v-if="row.is_default" class="sc-course__tag" size="small" type="info">
              默认
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column label="Embedding" min-width="220">
          <template #default="{ row }">
            <div>{{ row.embedding_model }}</div>
            <div class="sc-course__muted">
              {{ row.embedding_provider }} · {{ row.embedding_dim }} 维 · 版本 {{ row.index_version }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="索引状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.index_status === 'ACTIVE' ? 'success' : 'warning'" size="small">
              {{ indexStatusLabel(row.index_status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="课件" width="180">
          <template #default="{ row }">
            就绪 {{ row.ready_doc_count }} · 解析中 {{ row.parsing_doc_count }} · 失败
            {{ row.failed_doc_count }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openSwitch(row)">切换模型</el-button>
            <el-button
              text
              type="danger"
              :loading="deletingId === row.course_id"
              @click="onDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="creating" title="新建课程空间" width="520px">
      <el-form label-width="90px">
        <el-form-item label="课程名称" required>
          <el-input v-model="createForm.name" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.description"
            type="textarea"
            maxlength="500"
            show-word-limit
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creating = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="switchForm.course !== null"
      title="切换课程 Embedding 模型"
      width="560px"
      @update:model-value="(value: boolean) => { if (!value) switchForm.course = null }"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="切换会递增课程索引版本"
        description="切换会更新课程 Embedding 与索引版本并发布 REINDEX；重建期间文档暂停检索，探测失败时原课程配置保持不变。"
      />
      <el-form label-width="130px" class="sc-course__form">
        <el-form-item label="模型来源">
          <el-radio-group v-model="switchForm.useUserDefault">
            <el-radio :value="true">使用个人设置默认</el-radio>
            <el-radio :value="false">指定模型</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="!switchForm.useUserDefault">
          <el-form-item label="Embedding 供应商" required>
            <el-select v-model="switchForm.provider">
              <el-option
                v-for="item in EMBEDDING_PROVIDERS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="模型名称" required>
            <el-input v-model="switchForm.model" maxlength="128" show-word-limit />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="switchForm.course = null">取消</el-button>
        <el-button type="primary" :loading="switching" @click="submitSwitch">确认切换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sc-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: var(--sc-page-gap);
  padding: 30px 32px;
  overflow: hidden;
  border-radius: var(--sc-radius);
  background: linear-gradient(120deg, #eaf2ff 0%, #f7faff 48%, #eef3ff 100%);
  box-shadow: var(--sc-shadow-soft);
}

.sc-hero__copy {
  max-width: 620px;
}

.sc-hero__eyebrow {
  color: var(--sc-brand-strong);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1.4px;
}

.sc-hero h2 {
  margin: 10px 0 8px;
  color: #16233d;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.3px;
}

.sc-hero p {
  color: var(--sc-muted);
  font-size: 14px;
  line-height: 1.7;
}

.sc-hero__art {
  flex: 0 0 240px;
  opacity: 0.9;
}

.sc-hero__art svg {
  display: block;
  width: 100%;
  height: auto;
}

.sc-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sc-page-gap);
  margin-bottom: var(--sc-page-gap);
}

.sc-stat {
  padding: 18px 20px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow-soft);
}

.sc-stat__label {
  color: var(--sc-muted);
  font-size: 13px;
}

.sc-stat strong {
  display: block;
  margin: 8px 0 4px;
  color: var(--sc-text);
  font-size: 28px;
  font-weight: 650;
}

.sc-stat__hint {
  color: #94a3b8;
  font-size: 12px;
}

.sc-stat--danger strong {
  color: var(--sc-danger);
}

.sc-empty-art {
  width: 150px;
  height: auto;
}

.sc-course__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sc-course__skeleton {
  margin-top: 16px;
}

.sc-course__tag {
  margin-left: 8px;
}

.sc-course__muted {
  color: #909399;
  font-size: 12px;
}

.sc-course__form {
  margin-top: 16px;
}

@media (max-width: 1100px) {
  .sc-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .sc-hero {
    padding: 22px;
  }

  .sc-hero h2 {
    font-size: 21px;
  }

  .sc-hero__art {
    display: none;
  }

  .sc-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
