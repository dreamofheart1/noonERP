package com.yx.framework.security.site;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import com.yx.framework.configversion.RuleConfigWriteService;
import static org.junit.jupiter.api.Assertions.*;

/** 新建隔离 MySQL，执行 schema v0.5 原始 DDL，测试真实 Spring 事务和锁。 */
class MySqlRuleConfigWriteIT extends MySqlConfigHistoryIT
{
    @Override protected Class<?> configClass() { return ConfigWithWriter.class; }

    @Configuration
    static class ConfigWithWriter extends MySqlSiteAuthorizationIT.MySqlConfig
    {
        @Bean RuleConfigWriteService writer(DataSource dataSource, SiteAuthGuard guard)
        { return new RuleConfigWriteService(new JdbcTemplate(dataSource), guard); }
    }

    private void tables() throws Exception
    {
        String ddl = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String name : List.of("noon_rule_config", "noon_rule_config_lock", "noon_rule_config_audit"))
        {
            String statement = ddl.substring(ddl.indexOf("CREATE TABLE IF NOT EXISTS " + name + " ("));
            jdbc.execute(statement.substring(0, statement.indexOf(';') + 1));
        }
    }

    @Test void firstConcurrentWritesSerializeAndOnlyOneOverlappingVersionCommits() throws Exception
    {
        tables();
        var writer = context.getBean(RuleConfigWriteService.class);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try
        {
            var futures = List.of("15", "16").stream().map(value -> pool.submit(() -> {
                login(1L);
                try
                {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { writer.create("test_concurrent", "AE", value, LocalDateTime.parse("2026-01-01T00:00:00"), null); return true; }
                    catch (IllegalStateException expected) { return false; }
                }
                finally { SecurityContextHolder.clearContext(); }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config WHERE config_key='test_concurrent'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_audit WHERE config_key='test_concurrent'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_lock WHERE config_key='test_concurrent'", Integer.class));
        }
        finally { pool.shutdownNow(); }
    }

    @Test void auditFailureRollsBackVersionAndFirstLockRow()
            throws Exception
    {
        tables();
        jdbc.execute("ALTER TABLE noon_rule_config_audit ADD CONSTRAINT reject_create CHECK (action <> 'CREATE')");
        var writer = context.getBean(RuleConfigWriteService.class);
        assertThrows(RuntimeException.class, () -> writer.create("test_rollback", "GLOBAL", "x", LocalDateTime.parse("2026-01-01T00:00:00"), null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config WHERE config_key='test_rollback'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_lock WHERE config_key='test_rollback'", Integer.class));
    }

    @Test void firstLockWaiterProceedsAfterBlockingTransactionRollsBack() throws Exception
    {
        tables();
        var writer = context.getBean(RuleConfigWriteService.class);
        var pool = Executors.newSingleThreadExecutor();
        try (var blocker = context.getBean(DataSource.class).getConnection())
        {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("INSERT INTO noon_rule_config_lock(config_key,scope) VALUES ('test_wait','AE')"))
            { statement.executeUpdate(); }
            var future = pool.submit(() -> {
                login(1L);
                try { return writer.create("test_wait", "AE", "x", LocalDateTime.parse("2026-01-01T00:00:00"), null); }
                finally { SecurityContextHolder.clearContext(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline)
            {
                waiting = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class) > 0;
                if (waiting) break;
                Thread.sleep(10);
            }
            assertTrue(waiting, "必须真实观察到首次锁行等待");
            blocker.rollback();
            assertTrue(future.get(10, TimeUnit.SECONDS) > 0);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config WHERE config_key='test_wait'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_audit WHERE config_key='test_wait'", Integer.class));
        }
        finally { pool.shutdownNow(); }
    }

    @Test void revokeAuditsAndOrdinaryUsersCannotWriteEvenWithSiteGrant() throws Exception
    {
        tables();
        var writer = context.getBean(RuleConfigWriteService.class);
        long id = writer.create("test_revoke", "AE", "15", LocalDateTime.parse("2026-01-01T00:00:00"), null);
        writer.revoke(id);
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM noon_rule_config WHERE id=?", LocalDateTime.class, id));
        assertEquals(List.of("CREATE", "REVOKE"), jdbc.queryForList("SELECT action FROM noon_rule_config_audit WHERE config_key='test_revoke' ORDER BY id", String.class));
        assertThrows(IllegalStateException.class, () -> writer.revoke(id));
        long failingId = writer.create("test_revoke_fail", "GLOBAL", "14", LocalDateTime.parse("2026-01-01T00:00:00"), null);
        jdbc.execute("ALTER TABLE noon_rule_config_audit ADD CONSTRAINT reject_revoke CHECK (config_key <> 'test_revoke_fail' OR action <> 'REVOKE')");
        assertThrows(RuntimeException.class, () -> writer.revoke(failingId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config WHERE id=? AND revoked_at IS NULL AND revoked_by IS NULL AND status=1", Integer.class, failingId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_audit WHERE config_key='test_revoke_fail'", Integer.class));
        service.replaceUserSites(2L, List.of("AE"));
        login(2L);
        assertThrows(AccessDeniedException.class, () -> writer.create("test_denied", "AE", "x", LocalDateTime.parse("2026-01-01T00:00:00"), null));
        assertThrows(AccessDeniedException.class, () -> writer.create("test_denied", "GLOBAL", "x", LocalDateTime.parse("2026-01-01T00:00:00"), null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM noon_rule_config_lock WHERE config_key='test_denied'", Integer.class));
    }
}
