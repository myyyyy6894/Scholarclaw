package com.scholarclaw.common.event;

import java.time.Instant;

/** 任务取消与各领域清理在同一事务中完成；不包含通知原文。 */
public record DdlTaskCanceledEvent(long userId, long taskId, Instant canceledAt) { }
