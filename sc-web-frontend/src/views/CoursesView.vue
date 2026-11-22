<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { MoreHorizontal, Plus, Search } from '@lucide/vue'
import { ElMessageBox } from 'element-plus'

import {
  changeCourseEmbedding,
  createCourse,
  deleteCourse,
  listCourses,
} from '@/api/course'
import type { CourseNamespace } from '@/api/course'
import { listDdlTasks } from '@/api/ddl'
import type { EmbeddingProvider } from '@/api/user-config'
import CourseCard from '@/components/course/CourseCard.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { filterCourses } from '@/views/course/course-list'
import {
  CourseFormValidationError,
  toChangeEmbeddingRequest,
  toCreateCourseRequest,
} from '@/views/course/course-form'
import { groupDdlCountByCourse } from '@/views/home/dashboard-data'

/**
 * 我的课程（P3，参考图 `docs/ui_picture/我的课程.png`）。
 *
 * 只展示后端真实返回的课程、Embedding 绑定、索引状态与文档计数；
 * 课程搜索只过滤已加载的课程名称/备注，不伪造后端搜索能力。
 */
const EMBEDDING_PROVIDERS: Array<{ value: EmbeddingProvider; label: string }> = [
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'ZHIPU', label: '智谱 GLM' },
  { value: 'OLLAMA', label: 'Ollama（本地/自建）' },
]
const TONES = ['blue', 'green', 'purple', 'orange'] as const

const loading = ref(true)
const loadError = ref('')
const courses = ref<CourseNamespace[]>([])
const ddlCounts = ref<Record<string, number> | null>(null)
const searchQuery = ref('')
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
const filteredCourses = computed(() => filterCourses(courses.value, searchQuery.value))

onMounted(() => {
  void loadCourses()
  void loadDdlCounts()
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

async function loadDdlCounts(): Promise<void> {
  try {
    const board = await listDdlTasks({ statusGroup: 'ALL' })
    ddlCounts.value = groupDdlCountByCourse(board)
  } catch {
    // DDL 计数是辅助信息；失败时卡片显示「—」，不打断课程管理
    ddlCounts.value = null
  }
}

function ddlCountFor(courseId: string): number | null {
  return ddlCounts.value === null ? null : (ddlCounts.value[courseId] ?? 0)
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
  if (course === null || switching.value) {
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
    await Promise.all([loadCourses(), loadDdlCounts()])
  } catch (error) {
    notifyError(error)
  } finally {
    deletingId.value = null
  }
}

function deleteCourseFromMenu(course: CourseNamespace): void {
  void onDelete(course)
}

function toneFor(index: number): (typeof TONES)[number] {
  return TONES[index % TONES.length] ?? 'blue'
}
</script>

<template>
  <section class="courses-page" aria-labelledby="courses-heading">
    <header class="courses-toolbar">
      <h2 id="courses-heading">课程空间</h2>
      <label class="courses-search">
        <Search :size="16" aria-hidden="true" />
        <input v-model="searchQuery" type="search" placeholder="搜索课程" aria-label="搜索课程" />
      </label>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
    </header>

    <div v-if="loading" class="courses-grid" aria-hidden="true">
      <div v-for="index in 6" :key="index" class="course-skeleton" />
    </div>

    <BaseCard v-else-if="loadError !== ''" title="课程读取失败">
      <div class="module-error">
        <p>{{ loadError }}</p>
        <el-button size="small" @click="loadCourses">重新加载</el-button>
      </div>
    </BaseCard>

    <BaseCard v-else-if="!hasCourses">
      <EmptyState
        image="/illustrations/scene-study-desk.png"
        title="还没有课程空间"
        description="创建第一门课程，上传课件后即可使用知识库问答与 DDL 提醒。"
      >
        <template #action>
          <el-button type="primary" @click="openCreate">新建课程</el-button>
        </template>
      </EmptyState>
    </BaseCard>

    <BaseCard v-else-if="filteredCourses.length === 0">
      <EmptyState
        title="没有匹配的课程"
        :description="`没有找到与「${searchQuery}」匹配的课程名称或备注。`"
      >
        <template #action>
          <el-button @click="searchQuery = ''">清除搜索</el-button>
        </template>
      </EmptyState>
    </BaseCard>

    <div v-else class="courses-grid">
      <div
        v-for="(course, index) in filteredCourses"
        :key="course.course_id"
        class="courses-grid__item"
        :class="{ 'is-deleting': deletingId === course.course_id }"
      >
        <CourseCard
          :course="course"
          :ddl-count="ddlCountFor(course.course_id)"
          :tone="toneFor(index)"
        >
          <template #actions>
            <el-dropdown trigger="click" placement="bottom-end">
              <button type="button" class="course-menu" aria-label="课程操作" title="课程操作">
                <MoreHorizontal :size="16" :stroke-width="1.8" />
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openSwitch(course)">切换 Embedding</el-dropdown-item>
                  <el-dropdown-item divided @click="deleteCourseFromMenu(course)">删除课程</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </CourseCard>
      </div>
    </div>

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
      <el-form label-width="130px" class="course-switch-form">
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
  </section>
</template>

<style scoped>
.courses-page { display: grid; gap: 14px; scroll-margin-top: 16px; }
.courses-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.courses-toolbar h2 { margin: 0 auto 0 0; font-size: 20px; }
.courses-search {
  display: flex; align-items: center; gap: 8px; padding: 6px 10px;
  border: 1px solid var(--sc-border); border-radius: 8px; color: var(--sc-text-secondary);
}
.courses-search:focus-within { border-color: var(--sc-primary); }
.courses-search input { outline: none; border: 0; background: transparent; color: var(--sc-text); width: 120px; font: inherit; }
.courses-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 250px), 1fr)); gap: 14px; }
.courses-grid__item { display: grid; min-width: 0; }
.courses-grid__item.is-deleting { opacity: 0.6; pointer-events: none; }
.course-skeleton { min-height: 116px; border: 1px solid var(--sc-border); border-radius: 12px; }
.course-menu { border: 0; padding: 5px; background: transparent; cursor: pointer; color: var(--sc-text-secondary); }
.course-menu:hover { color: var(--sc-primary); }
.module-error p { color: var(--sc-text-secondary); }
.course-switch-form { margin-top: 16px; }
</style>
