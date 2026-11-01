<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { Library, MessagesSquare, MoreHorizontal, Plus, Search, Upload } from '@lucide/vue'
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
import ComingSoonBadge from '@/components/ui/ComingSoonBadge.vue'
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
 * 「本学期 / 已归档」与学期下拉后端没有字段，只做禁用占位并标注「暂未开发」；
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
const summary = computed(() => ({
  courses: courses.value.length,
  ready: courses.value.reduce((total, item) => total + item.ready_doc_count, 0),
  parsing: courses.value.reduce((total, item) => total + item.parsing_doc_count, 0),
  failed: courses.value.reduce((total, item) => total + item.failed_doc_count, 0),
}))

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
  <div class="courses-page">
    <div class="courses-main">
      <section class="courses-hero">
        <div class="courses-hero__copy">
          <span class="courses-hero__eyebrow">COURSE WORKSPACE</span>
          <h2>我的课程</h2>
          <p>管理你的课程空间，关联知识库，让 AI 更好地为你的学习服务。</p>
          <div class="courses-hero__summary">
            <span>课程 {{ summary.courses }}</span>
            <span>就绪 {{ summary.ready }}</span>
            <span>解析中 {{ summary.parsing }}</span>
            <span v-if="summary.failed > 0" class="is-danger">失败 {{ summary.failed }}</span>
          </div>
        </div>
        <div class="courses-hero__side">
          <div class="courses-hero__art">
            <img src="/illustrations/course-header-books.png" alt="" />
          </div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建课程</el-button>
        </div>
      </section>

      <div class="courses-toolbar">
        <div class="courses-tabs">
          <button type="button" class="course-tab course-tab--active">
            全部课程 <span class="course-tab__count">{{ courses.length }}</span>
          </button>
          <button type="button" class="course-tab" disabled title="后端暂无学期字段">
            本学期 <ComingSoonBadge compact />
          </button>
          <button type="button" class="course-tab" disabled title="后端暂无归档字段">
            已归档 <ComingSoonBadge compact />
          </button>
        </div>

        <div class="courses-tools">
          <label class="courses-search">
            <Search :size="15" :stroke-width="1.8" aria-hidden="true" />
            <input v-model="searchQuery" type="search" placeholder="搜索课程…" aria-label="搜索课程" />
          </label>
          <el-select class="courses-term" disabled placeholder="学期（暂未开发）" />
        </div>
      </div>

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
    </div>

    <aside class="courses-rail">
      <BaseCard title="课程日历" subtitle="学期视图">
        <template #actions>
          <ComingSoonBadge compact />
        </template>
        <div class="rail-placeholder">
          <span class="rail-placeholder__art">
            <img src="/illustrations/status-schedule.png" alt="" loading="lazy" />
          </span>
          <p>课程日历功能暂未开发；后端暂无学期与课表数据。</p>
        </div>
      </BaseCard>

      <BaseCard title="本周课程">
        <template #actions>
          <ComingSoonBadge compact />
        </template>
        <EmptyState
          image="/illustrations/status-schedule.png"
          title="课表功能暂未开发"
          description="接入后这里会显示本周课程与上课时间。"
          compact
        />
      </BaseCard>

      <BaseCard title="课程快捷操作">
        <ul class="rail-actions">
          <li>
            <router-link class="rail-action" :to="{ name: 'knowledge' }">
              <span class="rail-action__icon"><Upload :size="16" :stroke-width="1.8" /></span>
              <span class="rail-action__text">
                <strong>添加学习资料</strong>
                <small>上传文档，关联到课程</small>
              </span>
            </router-link>
          </li>
          <li>
            <router-link class="rail-action" :to="{ name: 'knowledge' }">
              <span class="rail-action__icon"><Library :size="16" :stroke-width="1.8" /></span>
              <span class="rail-action__text">
                <strong>查看课程知识库</strong>
                <small>管理课程相关的所有文档</small>
              </span>
            </router-link>
          </li>
          <li>
            <router-link class="rail-action" :to="{ name: 'rag' }">
              <span class="rail-action__icon"><MessagesSquare :size="16" :stroke-width="1.8" /></span>
              <span class="rail-action__text">
                <strong>课程问答</strong>
                <small>基于本课程的知识进行提问</small>
              </span>
            </router-link>
          </li>
        </ul>
      </BaseCard>

      <div class="rail-quote">
        <img src="/illustrations/scene-study-desk.png" alt="" loading="lazy" />
        <p>在知识的海洋里<br />找到属于你的方向</p>
      </div>
    </aside>

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
  </div>
</template>

<style scoped>
.courses-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--sc-card-gap);
  align-items: start;
}

.courses-main {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  min-width: 0;
}

.courses-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 26px 30px;
  overflow: hidden;
  border: 1px solid var(--sc-border);
  border-radius: 16px;
  background: linear-gradient(
    120deg,
    var(--sc-primary-soft) 0%,
    var(--sc-surface) 55%,
    var(--sc-accent-soft) 100%
  );
  box-shadow: var(--sc-shadow);
}

.courses-hero__copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
}

.courses-hero__eyebrow {
  color: var(--sc-primary-strong);
  font-size: var(--sc-font-hint);
  font-weight: 700;
  letter-spacing: 1.2px;
}

.courses-hero h2 {
  color: var(--sc-text);
  font-size: var(--sc-font-title);
  font-weight: var(--sc-font-title-weight);
  letter-spacing: -0.3px;
}

.courses-hero p {
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
  line-height: 1.7;
}

.courses-hero__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}

.courses-hero__summary span {
  padding: 2px 9px;
  border-radius: var(--sc-radius-pill);
  color: var(--sc-text-secondary);
  background: var(--sc-surface);
  font-size: var(--sc-font-hint);
}

.courses-hero__summary span.is-danger {
  color: var(--sc-danger);
  background: var(--sc-danger-soft);
}

.courses-hero__side {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
}

.courses-hero__art {
  display: grid;
  place-items: center;
  width: 116px;
  height: 100px;
  padding: 6px;
  border-radius: 14px;
  background: #f1f6ff;
}

.courses-hero__art img {
  display: block;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.courses-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 2px;
  border-bottom: 1px solid var(--sc-border);
}

.courses-tabs {
  display: flex;
  align-items: center;
  gap: 18px;
  min-width: 0;
}

.course-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 2px 10px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--sc-text-secondary);
  background: transparent;
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-body);
  cursor: pointer;
}

.course-tab--active {
  border-bottom-color: var(--sc-primary);
  color: var(--sc-primary-strong);
  font-weight: 600;
}

.course-tab:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.course-tab__count {
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--sc-radius-pill);
  color: var(--sc-primary-strong);
  background: var(--sc-primary-soft);
  font-size: var(--sc-font-hint);
  line-height: 18px;
  text-align: center;
}

.courses-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.courses-search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-pill);
  color: var(--sc-text-placeholder);
  background: var(--sc-surface);
}

.courses-search:focus-within {
  border-color: var(--sc-primary);
  box-shadow: 0 0 0 3px var(--sc-primary-soft);
}

.courses-search input {
  width: 150px;
  border: 0;
  outline: 0;
  color: var(--sc-text);
  background: transparent;
  font-family: var(--sc-font-sans);
  font-size: var(--sc-font-caption);
}

.courses-search input::-webkit-search-cancel-button {
  display: none;
}

.courses-term {
  width: 168px;
}

.courses-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.courses-grid__item {
  min-width: 0;
}

.courses-grid__item.is-deleting {
  opacity: 0.55;
  pointer-events: none;
}

.course-skeleton {
  height: 188px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-card);
  background: var(--sc-surface-muted);
}

.course-menu {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--sc-border);
  border-radius: 7px;
  color: var(--sc-text-placeholder);
  background: var(--sc-surface);
  cursor: pointer;
}

.course-menu:hover {
  border-color: var(--sc-primary);
  color: var(--sc-primary);
}

.courses-rail {
  display: flex;
  flex-direction: column;
  gap: var(--sc-card-gap);
  min-width: 0;
}

.rail-placeholder {
  display: flex;
  align-items: center;
  gap: 12px;
}

.rail-placeholder__art {
  display: grid;
  flex: none;
  place-items: center;
  width: 64px;
  height: 64px;
  padding: 6px;
  border-radius: 12px;
  background: #f1f6ff;
}

.rail-placeholder__art img {
  display: block;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.rail-placeholder p {
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  line-height: 1.6;
}

.rail-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.rail-action {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
  transition: background-color 0.18s ease;
}

.rail-action:hover {
  background: var(--sc-surface-hover);
}

.rail-action__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: var(--sc-primary);
  background: var(--sc-primary-soft);
}

.rail-action__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.rail-action__text strong {
  color: var(--sc-text);
  font-size: 13px;
  font-weight: 600;
}

.rail-action__text small {
  overflow: hidden;
  color: var(--sc-text-placeholder);
  font-size: var(--sc-font-hint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-quote {
  position: relative;
  min-height: 112px;
  overflow: hidden;
  border-radius: var(--sc-radius-card);
  background: #eaf2ff;
}

.rail-quote img {
  display: block;
  width: 100%;
  height: 132px;
  object-fit: cover;
  opacity: 0.9;
}

.rail-quote p {
  position: absolute;
  left: 14px;
  bottom: 14px;
  color: #1f2937;
  font-size: var(--sc-font-caption);
  font-weight: 600;
  line-height: 1.6;
}

.module-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--sc-text-secondary);
  font-size: var(--sc-font-caption);
}

.course-switch-form {
  margin-top: 16px;
}

@media (max-width: 1360px) {
  .courses-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1199px) {
  .courses-page {
    grid-template-columns: 1fr;
  }

  .courses-rail {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

@media (max-width: 860px) {
  .courses-grid {
    grid-template-columns: 1fr;
  }

  .courses-rail {
    grid-template-columns: 1fr;
  }

  .courses-hero {
    padding: 20px;
  }

  .courses-hero__art {
    display: none;
  }
}

@media (max-width: 639px) {
  .courses-toolbar {
    align-items: stretch;
  }

  .courses-tabs {
    gap: 10px;
    overflow-x: auto;
  }

  .courses-tools {
    width: 100%;
  }

  .courses-search {
    flex: 1;
  }

  .courses-search input {
    width: 100%;
  }

  .courses-term {
    flex: none;
    width: 120px;
  }
}
</style>
