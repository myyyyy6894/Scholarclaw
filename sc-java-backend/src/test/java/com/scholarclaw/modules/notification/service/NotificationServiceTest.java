package com.scholarclaw.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.notification.dto.MarkReadResultVO;
import com.scholarclaw.modules.notification.dto.NotificationListVO;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.MybatisTableInfoTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** TASK-802 通知落库、DDL 文案、查询分页与已读幂等。 */
class NotificationServiceTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final long NOTIFICATION_ID = 70001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 10, 28, 2, 0);
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 28, 10, 0);

    private NotificationLogMapper mapper;
    private UserConfigService userConfigService;

    @BeforeAll
    static void initTableInfo() {
        MybatisTableInfoTestSupport.init(NotificationLogEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationLogMapper.class);
        userConfigService = mock(UserConfigService.class);
        doAnswer(invocation -> {
            NotificationLogEntity entity = invocation.getArgument(0);
            if (entity.getNotificationId() == null) {
                entity.setNotificationId(NOTIFICATION_ID);
            }
            return 1;
        }).when(mapper).insert(any(NotificationLogEntity.class));
    }

    @Test
    @DisplayName("IM 关闭时落库为 FALLBACK_WEB_ONLY，站内信字段完整")
    void createsWebOnlyNotificationWhenPushDisabled() {
        NotificationService service = service(false, DAY);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));

        Long id = service.createInTx(new NotificationCreateCommand(
                USER_ID, NotificationBizType.DOC_INGEST_READY, 50001L,
                "✅ 课件入库成功", "已归档至课程知识库。", "tr_test_0001"));

        assertThat(id).isEqualTo(NOTIFICATION_ID);
        NotificationLogEntity entity = capturedEntity();
        assertThat(entity.getUserId()).isEqualTo(USER_ID);
        assertThat(entity.getBizType()).isEqualTo("DOC_INGEST_READY");
        assertThat(entity.getBizId()).isEqualTo(50001L);
        assertThat(entity.getReadStatus()).isEqualTo("UNREAD");
        assertThat(entity.getOutboundStatus()).isEqualTo(OutboundStatus.FALLBACK_WEB_ONLY.name());
        assertThat(entity.getTargetImPlatform()).isEqualTo("QQ_ONEBOT");
        assertThat(entity.getTargetImUid()).isEqualTo("123456");
        assertThat(entity.getScheduledPushAt()).isNull();
        assertThat(entity.getImErrorMsg()).contains("总开关关闭");
        assertThat(entity.getImRetryCount()).isZero();
        assertThat(entity.getTraceId()).isEqualTo("tr_test_0001");
    }

    @Test
    @DisplayName("夜间非紧急提醒进入 RATE_LIMITED_DELAYED 并顺延到 07:30")
    void delaysNonUrgentNotificationInsideDnd() {
        NotificationService service = service(true, NIGHT);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));

        service.createInTx(new NotificationCreateCommand(
                USER_ID, NotificationBizType.DDL_T24H, TASK_ID,
                "提醒", "内容", null));

        NotificationLogEntity entity = capturedEntity();
        assertThat(entity.getOutboundStatus())
                .isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED.name());
        assertThat(entity.getScheduledPushAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 7, 30));
    }

    @Test
    @DisplayName("T-6h 紧急提醒不受免打扰影响，进入 PENDING_IM")
    void urgentNotificationIsPending() {
        NotificationService service = service(true, NIGHT);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));

        service.createInTx(new NotificationCreateCommand(
                USER_ID, NotificationBizType.DDL_T6H, TASK_ID,
                "紧急提醒", "内容", null));

        NotificationLogEntity entity = capturedEntity();
        assertThat(entity.getOutboundStatus()).isEqualTo(OutboundStatus.PENDING_IM.name());
        assertThat(entity.getScheduledPushAt()).isEqualTo(NIGHT);
    }

    @Test
    @DisplayName("DDL T-24h 文案包含任务编号、截止时间、时区与 23:59 推断说明")
    void formatsT24Reminder() {
        NotificationService service = service(false, DAY);

        service.createDdlReminderInTx(new DdlReminderNotification(
                USER_ID, TASK_ID, NotificationBizType.DDL_T24H,
                "操作系统原理", "提交实验二报告",
                LocalDateTime.of(2026, 10, 29, 23, 59), "Asia/Shanghai",
                true, 0, "tr_test_0002"));

        NotificationLogEntity entity = capturedEntity();
        assertThat(entity.getTitle()).contains("T-24h").contains("操作系统原理")
                .contains("提交实验二报告");
        assertThat(entity.getContent()).contains("#T103")
                .contains("2026-10-29 23:59").contains("Asia/Shanghai")
                .contains("未注明具体时刻");
        assertThat(entity.getBizType()).isEqualTo("DDL_T24H");
        assertThat(entity.getBizId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("DDL 逾期与跟进文案分别强调逾期和待确认次数")
    void formatsOverdueAndFollowUp() {
        NotificationService service = service(false, DAY);

        service.createDdlReminderInTx(new DdlReminderNotification(
                USER_ID, TASK_ID, NotificationBizType.DDL_OVERDUE,
                "操作系统原理", "提交实验二报告",
                LocalDateTime.of(2026, 10, 28, 9, 0), "Asia/Shanghai",
                false, 0, null));
        NotificationLogEntity overdue = capturedEntity();
        assertThat(overdue.getTitle()).contains("逾期");
        assertThat(overdue.getContent()).contains("已超过截止时间");

        service.createDdlReminderInTx(new DdlReminderNotification(
                USER_ID, TASK_ID + 1, NotificationBizType.DDL_FOLLOW_UP,
                "操作系统原理", "提交实验二报告", null, "Asia/Shanghai",
                false, 2, null));
        NotificationLogEntity followUp = capturedEntity();
        assertThat(followUp.getContent()).contains("第 2/2 次提醒")
                .contains("尚未确认截止时间");
    }

    @Test
    @DisplayName("非 DDL 类型不得调用 DDL 提醒入口")
    void rejectsNonDdlReminderType() {
        NotificationService service = service(false, DAY);
        assertThatThrownBy(() -> service.createDdlReminderInTx(
                new DdlReminderNotification(USER_ID, TASK_ID,
                        NotificationBizType.DOC_INGEST_READY,
                        "c", "t", null, "Asia/Shanghai", false, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insert(any(NotificationLogEntity.class));
    }

    @Test
    @DisplayName("查询分页：返回未读数、总数、总页数并按用户过滤")
    void listsNotificationsWithPagination() {
        NotificationService service = service(false, DAY);
        when(mapper.selectPage(USER_ID, "UNREAD", 20L, 20)).thenReturn(List.of(
                entity(NOTIFICATION_ID, "DDL_T24H", "UNREAD")));
        when(mapper.countByUser(USER_ID, "UNREAD")).thenReturn(21L);
        when(mapper.countUnread(USER_ID)).thenReturn(21L);

        NotificationListVO result = service.list(USER_ID, "UNREAD", 2, 20);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.unreadCount()).isEqualTo(21);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.notificationId()).isEqualTo(NOTIFICATION_ID);
            assertThat(item.bizType()).isEqualTo("DDL_T24H");
            assertThat(item.createdAt()).isEqualTo(
                    OffsetDateTime.parse("2026-10-28T10:00:00+08:00"));
        });
        verify(mapper).selectPage(USER_ID, "UNREAD", 20L, 20);
        verify(mapper).countByUser(USER_ID, "UNREAD");
        verify(mapper).countUnread(USER_ID);
    }

    @Test
    @DisplayName("查询参数：read_status 非法、size 超限均返回 400 校验错误")
    void rejectsInvalidListParameters() {
        NotificationService service = service(false, DAY);

        assertThatThrownBy(() -> service.list(USER_ID, "DELETED", 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.list(USER_ID, null, 1, 101))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).selectPage(anyLong(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("标记全部已读：只更新当前用户未读记录并返回剩余未读数")
    void marksAllUnreadNotifications() {
        NotificationService service = service(false, DAY);
        when(mapper.update(any(), any())).thenReturn(3);
        when(mapper.countUnread(USER_ID)).thenReturn(0L);

        MarkReadResultVO result = service.markRead(USER_ID, true, null);

        assertThat(result.updatedCount()).isEqualTo(3);
        assertThat(result.remainingUnreadCount()).isZero();
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("标记指定通知：ID 去重、十进制字符串解析、重复调用幂等")
    void marksSelectedNotificationsIdempotently() {
        NotificationService service = service(false, DAY);
        when(mapper.update(any(), any())).thenReturn(2);
        when(mapper.countUnread(USER_ID)).thenReturn(1L);

        MarkReadResultVO result = service.markRead(USER_ID, false,
                List.of("70001", "70002", "70001"));

        assertThat(result.updatedCount()).isEqualTo(2);
        assertThat(result.remainingUnreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("mark_all=false 时缺 ID、非法 ID 一律 400 且不写库")
    void rejectsInvalidMarkIds() {
        NotificationService service = service(false, DAY);

        assertThatThrownBy(() -> service.markRead(USER_ID, false, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.markRead(USER_ID, false, List.of("abc")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.markRead(USER_ID, false, List.of("0")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.markRead(USER_ID, false,
                List.of("99999999999999999999")))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("mark_all 为空返回 400")
    void rejectsNullMarkAll() {
        NotificationService service = service(false, DAY);
        assertThatThrownBy(() -> service.markRead(USER_ID, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("超长标题截断到 255 字符，避免 varchar 溢出回滚业务")
    void truncatesOverlongTitle() {
        NotificationService service = service(false, DAY);
        String longTitle = "标题".repeat(200);

        service.createInTx(new NotificationCreateCommand(
                USER_ID, NotificationBizType.COURSE_REBUILD_DONE, 1L,
                longTitle, "内容", null));

        assertThat(capturedEntity().getTitle()).hasSize(255).endsWith("…");
    }

    private NotificationService service(boolean pushEnabled, LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        return new NotificationService(mapper, userConfigService,
                new NotificationOutboundPolicy(), clock, pushEnabled);
    }

    private NotificationLogEntity capturedEntity() {
        ArgumentCaptor<NotificationLogEntity> captor =
                ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        List<NotificationLogEntity> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static UserConfigEntity config() {
        UserConfigEntity config = new UserConfigEntity();
        config.setAccountStatus("ACTIVE");
        config.setTimezone("Asia/Shanghai");
        config.setDndStartTime(LocalTime.of(23, 30));
        config.setDndEndTime(LocalTime.of(7, 30));
        config.setBoundImPlatform("QQ_ONEBOT");
        config.setBoundImUid("123456");
        return config;
    }

    private static NotificationLogEntity entity(long id, String bizType, String readStatus) {
        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setNotificationId(id);
        entity.setUserId(USER_ID);
        entity.setBizType(bizType);
        entity.setBizId(TASK_ID);
        entity.setTitle("标题");
        entity.setContent("内容");
        entity.setReadStatus(readStatus);
        entity.setOutboundStatus("FALLBACK_WEB_ONLY");
        entity.setCreatedAt(DAY);
        return entity;
    }
}
