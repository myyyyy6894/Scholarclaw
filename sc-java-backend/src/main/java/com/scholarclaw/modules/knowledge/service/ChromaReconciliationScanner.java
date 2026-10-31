package com.scholarclaw.modules.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.course.service.CourseService.ActiveCourseRef;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ChromaDB Collection 孤儿对账（P9 复核第 2 项）。
 *
 * <p>raw 文件孤儿扫描只能发现磁盘残留；课程行被物理删除后，ChromaDB 里仍可能留下
 * `sc_u{uid}_c{cid}_v{ver}` Collection。本扫描以 MySQL 活动课程集合为准：
 *
 * <ul>
 *   <li>期望集合 = 每个 `is_deleting=0` 课程的当前 `index_version`；</li>
 *   <li>实际集合来自 Python 只读接口 `GET /internal/ai/v1/rag/collections`；</li>
 *   <li>不属于期望集合、但符合 `sc_u<数字>_c<数字>_v<数字>` 命名的集合视为孤儿/旧版本，
 *       交给 {{@link KnowledgeVectorCleanupService}} 幂等删除；失败会写入 outbox 由 GC 重试。</li>
 * </ul>
 *
 * <p><b>安全边界：</b>只处理本应用命名的集合，绝不删除同机其他应用的 Collection；
 * 单轮最多处理 {@code batchSize} 个，单个失败不中断整轮。
 *
 * <p><b>与文档级清理的分工：</b>本扫描只做 Collection 级别的孤儿/旧版本对账；
 * 单个文档删除后的子块向量由 `DOCUMENT` 清理与 outbox 负责。课程处于 `REBUILDING` 时
 * 当前版本仍视为期望集合，旧版本会被删除，新版本写入不受影响。
 */
@Component
public class ChromaReconciliationScanner {

    private static final Logger log = LoggerFactory.getLogger(ChromaReconciliationScanner.class);
    private static final Pattern COLLECTION_PATTERN =
            Pattern.compile("^sc_u(\\d+)_c(\\d+)_v(\\d+)$");

    private final CourseService courseService;
    private final PythonAiEngineClient pythonClient;
    private final KnowledgeVectorCleanupService cleanupService;
    private final Duration readTimeout;

    public ChromaReconciliationScanner(
            CourseService courseService,
            PythonAiEngineClient pythonClient,
            KnowledgeVectorCleanupService cleanupService,
            @Value("${sc.deletion-gc.chroma-reconcile-read-timeout-seconds:30}")
            long readTimeoutSeconds) {
        this.courseService = courseService;
        this.pythonClient = pythonClient;
        this.cleanupService = cleanupService;
        this.readTimeout = Duration.ofSeconds(Math.max(1, readTimeoutSeconds));
    }

    /**
     * 执行一轮对账。
     *
     * @return 扫描到的应用集合数、删除成功数、失败数
     */
    public ScanResult reconcile(int batchSize) {
        JsonNode data;
        try {
            data = pythonClient.getJson(PythonAiEngineClient.VECTOR_COLLECTIONS_PATH, null,
                    readTimeout);
        } catch (RuntimeException e) {
            log.warn("ChromaDB Collection 对账无法读取列表，本轮跳过 error={}",
                    e.getClass().getSimpleName());
            return new ScanResult(0, 0, 0);
        }
        Set<String> expected = expectedCollections();
        JsonNode names = data == null ? null : data.get("names");
        if (names == null || !names.isArray()) {
            log.warn("ChromaDB Collection 对账响应缺少 names 数组，本轮跳过");
            return new ScanResult(0, 0, 0);
        }
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        int processed = 0;
        for (JsonNode node : names) {
            String name = node.asText("");
            Matcher matcher = COLLECTION_PATTERN.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            scanned++;
            if (expected.contains(name)) {
                continue;
            }
            if (processed >= Math.max(1, batchSize)) {
                log.info("ChromaDB Collection 对账达到单轮上限，剩余孤儿留待下一轮 batchSize={}",
                        batchSize);
                break;
            }
            processed++;
            Long userId = Long.valueOf(matcher.group(1));
            Long courseId = Long.valueOf(matcher.group(2));
            Integer version = Integer.valueOf(matcher.group(3));
            var result = cleanupService.cleanupCourse(userId, courseId, version);
            if (result.handled()) {
                deleted++;
                log.info("ChromaDB 孤儿 Collection 已清理 name={}", name);
            } else {
                failed++;
                log.error("ChromaDB 孤儿 Collection 清理未完成且未落库 name={}", name);
            }
        }
        return new ScanResult(scanned, deleted, failed);
    }

    private Set<String> expectedCollections() {
        Set<String> expected = new HashSet<>();
        for (ActiveCourseRef course : courseService.listActiveCourseRefs()) {
            if (course.userId() == null || course.courseId() == null
                    || course.indexVersion() == null) {
                continue;
            }
            expected.add("sc_u" + course.userId() + "_c" + course.courseId() + "_v"
                    + course.indexVersion());
        }
        return expected;
    }

    /** 单轮对账结果。 */
    public record ScanResult(int scanned, int deleted, int failed) {
    }
}
