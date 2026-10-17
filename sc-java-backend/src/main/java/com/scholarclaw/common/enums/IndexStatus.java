package com.scholarclaw.common.enums;

/**
 * 课程向量索引状态（{@code 02} §9.2；{@code t_course_namespace.index_status}）。
 *
 * <p>{@code REBUILDING} 期间拒绝再次切换课程 Embedding 模型或上传新课件（{@code E-SYS-03}）；
 * 若新 {@code index_version} 下尚无任何 {@code READY} 文档，RAG 问答返回
 * {@code COURSE_INDEX_REBUILDING}。
 */
public enum IndexStatus {
    ACTIVE,
    REBUILDING,
    REBUILD_FAILED
}
