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
      `已切换到 ${result.embedding_model}（维度 ${result.embedding_dim}，索引版本 ${result.current_index_version}）`,
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
        description="创建时会复制【个人设置】中已验证的默认 Embedding 配置；切换模型会重新探测并升级课程索引版本。"
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
      <el-empty v-else-if="!hasCourses" description="还没有课程空间，先新建一门课程。" />
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
        description="若课程下已有课件，将进入重建流程；探测失败时原课程配置保持不变。"
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
</style>
