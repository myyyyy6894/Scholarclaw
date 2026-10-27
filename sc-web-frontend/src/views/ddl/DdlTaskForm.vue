<script setup lang="ts">
import { computed } from 'vue'

import type { CourseNamespace } from '@/api/course'
import {
  MAX_COURSE_NAME,
  MAX_OPTIONAL_TEXT,
  MAX_TITLE,
  type DdlTaskFormState,
  type OriginalTimeInfo,
} from '@/views/ddl/ddl-task'

/**
 * DDL 任务表单（新建 `API-DDL-002` 与编辑 `API-DDL-004` 共用）。
 *
 * 只负责字段编辑，不提交；校验统一由 `taskFormError` 完成，避免两处规则漂移。
 * 时间字段随 `time_precision` 显隐：`VAGUE_OR_MISSING` 隐藏，`DATE_ONLY` 不显示时刻。
 *
 * Element Plus 的可清空控件在清空时可能写入 `null` / `undefined`，而表单状态的约定是
 * “空值一律为空字符串”；这里用可写 computed 兜住，避免下游 `.trim()` 崩溃。
 */

const form = defineModel<DdlTaskFormState>({ required: true })

const props = defineProps<{
  courses: CourseNamespace[]
  /** 编辑态用于提示“补充时间后会自动激活”。 */
  activateHint?: string
  /** 编辑态：原任务的时间语义快照；保存原字段不会改变截止时间/时区。 */
  originalTime?: OriginalTimeInfo | null
}>()

const emit = defineEmits<{
  (event: 'reconfirm'): void
}>()

function asText(value: string | null | undefined): string {
  return value ?? ''
}

const courseId = computed<string>({
  get: () => form.value.courseId,
  set: (value) => {
    form.value = { ...form.value, courseId: asText(value) }
  },
})

const dueDate = computed<string>({
  get: () => form.value.dueDate,
  set: (value) => {
    form.value = { ...form.value, dueDate: asText(value) }
  },
})

const dueTime = computed<string>({
  get: () => form.value.dueTime,
  set: (value) => {
    form.value = { ...form.value, dueTime: asText(value) }
  },
})

function onCourseChange(value: string | null | undefined): void {
  const selected = asText(value)
  if (selected === '') {
    form.value = { ...form.value, courseId: '', courseName: form.value.courseName }
    return
  }
  const course = props.courses.find((item) => item.course_id === selected)
  form.value = {
    ...form.value,
    courseId: selected,
    courseName: course === undefined ? form.value.courseName : course.course_name,
  }
}
</script>

<template>
  <el-form label-position="top" class="sc-ddl-form">
    <div v-if="props.originalTime" class="sc-ddl-origin">
      <div class="sc-ddl-origin__title">原任务时间（保存非时间字段不会改变这些值）</div>
      <div>原时间精度：{{ props.originalTime.precision }}</div>
      <div>原截止时间：{{ props.originalTime.deadline }}</div>
      <div>原用户时区：{{ props.originalTime.timezone }}</div>
      <div v-if="props.originalTime.reason">原确认原因：{{ props.originalTime.reason }}</div>
      <el-alert
        v-if="props.originalTime.needsReconfirm"
        class="sc-ddl-form__hint"
        type="warning"
        :closable="false"
        show-icon
        title="原时间非法或已过期，仅修改标题/交付要求不会改变截止时间"
        description="如需激活任务，请显式修改截止日期与时刻后保存，或点击下方“确认当前时间并激活”。"
      />
      <el-button
        v-if="props.originalTime.needsReconfirm"
        size="small"
        type="warning"
        plain
        @click="emit('reconfirm')"
      >
        确认当前时间并激活
      </el-button>
    </div>

    <el-alert
      v-if="activateHint"
      class="sc-ddl-form__hint"
      type="info"
      :closable="false"
      show-icon
      :title="activateHint"
    />

    <el-form-item label="关联课程">
      <el-select
        v-model="courseId"
        clearable
        filterable
        placeholder="不关联课程库时留空，仅保留课程名称文本"
        class="sc-ddl-form__control"
        @change="onCourseChange"
      >
        <el-option
          v-for="course in courses"
          :key="course.course_id"
          :label="course.course_name"
          :value="course.course_id"
        />
      </el-select>
    </el-form-item>

    <el-form-item label="课程 / 分类名称" required>
      <el-input
        v-model="form.courseName"
        :maxlength="MAX_COURSE_NAME"
        show-word-limit
        placeholder="如：操作系统原理"
      />
    </el-form-item>

    <el-form-item label="任务标题" required>
      <el-input
        v-model="form.taskTitle"
        :maxlength="MAX_TITLE"
        show-word-limit
        placeholder="如：提交实验二报告与源码"
      />
    </el-form-item>

    <el-form-item label="时间精度" required>
      <el-radio-group v-model="form.timePrecision">
        <el-radio-button value="EXACT_DATETIME">精确到时刻</el-radio-button>
        <el-radio-button value="DATE_ONLY">仅日期</el-radio-button>
        <el-radio-button value="VAGUE_OR_MISSING">暂不设定</el-radio-button>
      </el-radio-group>
    </el-form-item>

    <el-form-item
      v-if="form.timePrecision !== 'VAGUE_OR_MISSING'"
      label="截止日期"
      required
    >
      <el-date-picker
        v-model="dueDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择截止日期"
        class="sc-ddl-form__control"
      />
    </el-form-item>

    <el-form-item v-if="form.timePrecision === 'EXACT_DATETIME'" label="截止时刻" required>
      <el-time-picker
        v-model="dueTime"
        value-format="HH:mm:ss"
        placeholder="选择截止时刻"
        class="sc-ddl-form__control"
      />
    </el-form-item>

    <el-form-item v-else-if="form.timePrecision === 'DATE_ONLY'">
      <div class="sc-ddl-form__note">
        仅日期任务不会伪造具体时刻：后端按当日 23:59:59 统计倒计时，并在卡片上标注“原通知未注明具体时刻”。
      </div>
    </el-form-item>

    <el-form-item v-else>
      <div class="sc-ddl-form__note">
        暂不设定时间的任务会进入待确认收件箱，不会触发 T-24h / T-6h / 逾期提醒。
      </div>
    </el-form-item>

    <el-form-item label="交付物格式">
      <el-input
        v-model="form.deliverableFormat"
        :maxlength="MAX_OPTIONAL_TEXT"
        show-word-limit
        placeholder="如：学号_姓名.zip"
      />
    </el-form-item>

    <el-form-item label="提交渠道">
      <el-input
        v-model="form.submissionChannel"
        :maxlength="MAX_OPTIONAL_TEXT"
        show-word-limit
        placeholder="如：学习通作业区"
      />
    </el-form-item>
  </el-form>
</template>

<style scoped>
.sc-ddl-form__control {
  width: 100%;
}

.sc-ddl-form__hint {
  margin-bottom: 14px;
}

.sc-ddl-form__note {
  color: #7b8794;
  font-size: 12px;
  line-height: 1.7;
}

.sc-ddl-origin {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #f0e2c8;
  border-radius: 10px;
  background: #fffdf7;
  color: #6f5a2e;
  font-size: 12px;
  line-height: 1.7;
}

.sc-ddl-origin__title {
  color: #4c3a17;
  font-weight: 600;
}
</style>
