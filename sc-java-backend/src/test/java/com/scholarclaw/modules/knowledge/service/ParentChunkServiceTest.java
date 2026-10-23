package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchRequest;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchVO;
import com.scholarclaw.modules.knowledge.mapper.AuthoritativeParentChunkRow;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParentChunkServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;

    private KnowledgeParentChunkMapper mapper;
    private ParentChunkService service;

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeParentChunkMapper.class);
        service = new ParentChunkService(mapper);
    }

    @Test
    @DisplayName("按请求顺序返回权威父块，去重并允许部分命中")
    void returnsValidRowsInRequestedOrder() {
        when(mapper.selectAuthoritativeBatch(USER_ID, COURSE_ID, 3,
                List.of("pc_2", "pc_1")))
                .thenReturn(List.of(row("pc_1", 50001L, 1, 2, "内容一"),
                        row("pc_2", 50002L, 3, 3, "内容二")));

        ParentChunkBatchVO result = service.batchGet(USER_ID,
                request("10001", "2001", 3, List.of("pc_2", "pc_1", "pc_2")));

        assertThat(result.parentChunks()).extracting("parentChunkId")
                .containsExactly("pc_2", "pc_1");
        assertThat(result.parentChunks().get(0).docId()).isEqualTo(50002L);
        assertThat(result.parentChunks().get(0).pageStart()).isEqualTo(3);
        verify(mapper).selectAuthoritativeBatch(USER_ID, COURSE_ID, 3, List.of("pc_2", "pc_1"));
    }

    @Test
    @DisplayName("失效/不存在的候选被直接剔除，返回空列表而不是伪造父块")
    void dropsMissingCandidates() {
        when(mapper.selectAuthoritativeBatch(USER_ID, COURSE_ID, 1, List.of("pc_1", "pc_2")))
                .thenReturn(List.of());

        ParentChunkBatchVO result = service.batchGet(USER_ID,
                request("10001", "2001", 1, List.of("pc_1", "pc_2")));

        assertThat(result.parentChunks()).isEmpty();
    }

    @Test
    @DisplayName("字段不完整的权威行不进入证据集合")
    void dropsInvalidRows() {
        AuthoritativeParentChunkRow blankContent = row("pc_1", 50001L, 1, 2, " ");
        AuthoritativeParentChunkRow invertedPages = row("pc_2", 50001L, 5, 4, "内容");
        when(mapper.selectAuthoritativeBatch(USER_ID, COURSE_ID, 1, List.of("pc_1", "pc_2")))
                .thenReturn(List.of(blankContent, invertedPages));

        ParentChunkBatchVO result = service.batchGet(USER_ID,
                request("10001", "2001", 1, List.of("pc_1", "pc_2")));

        assertThat(result.parentChunks()).isEmpty();
    }

    @Test
    @DisplayName("请求体 user_id 与 X-User-Id 不一致返回 403")
    void rejectsIdentityMismatch() {
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("10002", "2001", 1, List.of("pc_1"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        verify(mapper, never()).selectAuthoritativeBatch(any(), any(), anyInt(), anyList());
    }

    @Test
    @DisplayName("候选数量与 ID 格式在进入 SQL 前校验")
    void validatesCandidates() {
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("10001", "2001", 1, List.of("p1", "p2", "p3", "p4", "p5"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("10001", "2001", 1, List.of("pc 1"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("10001", "2001", 1, List.of())))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).selectAuthoritativeBatch(any(), any(), anyInt(), anyList());
    }

    @Test
    @DisplayName("非法 user_id/course_id 在进入 SQL 前拒绝")
    void validatesIdentityFields() {
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("abc", "2001", 1, List.of("pc_1"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.batchGet(USER_ID,
                request("10001", "0", 1, List.of("pc_1"))))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).selectAuthoritativeBatch(any(), any(), anyInt(), anyList());
    }

    private static ParentChunkBatchRequest request(String userId, String courseId, int version,
                                                   List<String> ids) {
        return new ParentChunkBatchRequest(userId, courseId, version, ids);
    }

    private static AuthoritativeParentChunkRow row(String parentChunkId, long docId,
                                                   int pageStart, int pageEnd, String content) {
        AuthoritativeParentChunkRow row = new AuthoritativeParentChunkRow();
        row.setParentChunkId(parentChunkId);
        row.setDocId(docId);
        row.setDocName("a.pdf");
        row.setPageStart(pageStart);
        row.setPageEnd(pageEnd);
        row.setSectionTitle("5.1");
        row.setContent(content);
        return row;
    }
}
