package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Opt-in integration test: verifies the exact temporary MySQL datadir before Spring can migrate. */
@EnabledIfSystemProperty(named = "sc.qq.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false",
        "sc.notification.outbound.enabled=false",
        "sc.ddl.reminder.enabled=false",
        "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false",
        "sc.dependencies.redis.pool-probe=false"
})
class QqBotBindingIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:13306/scholarclaw_unit_test"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String expected = System.getProperty("sc.qq.test-datadir", "");
        if (!expected.startsWith("/tmp/scholarclaw-test-mysql.") || !expected.endsWith("/")) {
            throw new IllegalStateException("Explicit temporary MySQL datadir required");
        }
        try (var connection = DriverManager.getConnection(URL, "scholarclaw", "temporary-test-only");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT @@datadir, DATABASE()")) {
            if (!result.next() || !expected.equals(result.getString(1))
                    || !"scholarclaw_unit_test".equals(result.getString(2))) {
                throw new IllegalStateException("Refusing to run against non-test database");
            }
        }
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "scholarclaw");
        registry.add("spring.datasource.password", () -> "temporary-test-only");
    }

    @Autowired UserConfigService configs;
    @Autowired QqBotBindingService binding;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenRevocationService revocations;

    @Test
    void challengeEndpointRequiresJwtAndReturnsNoStore() throws Exception {
        var user = configs.createUserAccount("qqweb_" + System.nanoTime(), "test-hash", "Asia/Shanghai");
        configs.saveQqBotConfig(user.getUserId(), "3" + System.nanoTime(), "cipher");
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        mvc.perform(post("/api/v1/user/qqbot/binding-challenge"))
                .andExpect(status().isUnauthorized());
        String bearer = jwt.issue(user.getUserId(), user.getUsername(), "STUDENT").token();
        mvc.perform(post("/api/v1/user/qqbot/binding-challenge").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.expires_in_seconds").value(300));
        assertThat(configs.findById(user.getUserId()).orElseThrow().getQqbotChallengeHash()).hasSize(64);
    }

    @Test
    void challengeIsAtomicAndBotReplacementInvalidatesPriorIdentity() throws Exception {
        UserConfigEntity user = configs.createUserAccount("qqtest_" + System.nanoTime(), "test-hash", "Asia/Shanghai");
        Long userId = user.getUserId();
        String unique = Long.toString(System.nanoTime());
        UserConfigEntity first = configs.saveQqBotConfig(userId, "1" + unique, "cipher");
        String token = binding.issue(userId);
        assertThat(configs.findById(userId).orElseThrow().getQqbotChallengeHash())
                .hasSize(64).doesNotContain(token);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("owner-a", "owner-b").stream().map(openid -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                return binding.consume(message(userId, first, openid, false), token);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(futures.stream().map(f -> {
                try { return f.get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        UserConfigEntity bound = configs.findById(userId).orElseThrow();
        assertThat(bound.getQqbotBindingVerified()).isEqualTo(1);
        assertThat(bound.getQqbotOpenid()).isIn("owner-a", "owner-b");
        assertThat(bound.getQqbotChallengeHash()).isNull();
        assertThat(binding.consume(message(userId, first, "late-owner", false), token)).isFalse();

        String oldChallenge = binding.issue(userId);
        UserConfigEntity second = configs.saveQqBotConfig(userId, "2" + unique, "new-cipher");
        assertThat(second.getQqbotOpenid()).isNull();
        assertThat(second.getQqbotBindingVerified()).isZero();
        assertThat(second.getQqbotBindingVersion()).isGreaterThan(first.getQqbotBindingVersion());
        assertThat(binding.consume(message(userId, first, "late-owner", false), oldChallenge)).isFalse();

        String expired = binding.issue(userId);
        jdbc.update("UPDATE t_user_config SET qqbot_challenge_expires_at = '2000-01-01 00:00:00' WHERE user_id = ?", userId);
        assertThat(binding.consume(message(userId, second, "owner-a", false), expired)).isFalse();
        String current = binding.issue(userId);
        assertThat(binding.consume(message(userId, second, "owner-a", true), current)).isFalse();
        assertThat(binding.consume(message(userId, second, "owner-a", false), current)).isTrue();
    }

    private static QqBotConnection.InboundMessage message(Long userId, UserConfigEntity config,
                                                          String openid, boolean group) {
        return new QqBotConnection.InboundMessage(userId, config.getQqbotAppId(),
                config.getQqbotBindingVersion(), openid, "", "message", group, null);
    }
}
