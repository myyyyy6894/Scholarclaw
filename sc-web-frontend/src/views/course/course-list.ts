import type { CourseNamespace } from '@/api/course'

/**
 * 课程页的客户端筛选（P3）。
 *
 * 后端没有课程搜索/学期字段，因此这里只做**已有数据**的名称/备注过滤；
 * 不把“学期”“已归档”伪装成后端过滤。
 */
export function filterCourses(
  courses: CourseNamespace[],
  query: string,
): CourseNamespace[] {
  const keyword = query.trim().toLowerCase()
  if (keyword === '') {
    return courses
  }
  return courses.filter((course) => {
    const name = course.course_name.toLowerCase()
    const description = (course.description ?? '').toLowerCase()
    return name.includes(keyword) || description.includes(keyword)
  })
}
