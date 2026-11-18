package com.yx.framework.security.site;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 显式 opt-in：由 scripts/verify-site-auth-runtime.py 自建临时 MySQL。
 * 继承全部权限/事务测试，使用未经变换的原生 DDL；绝不读取应用数据源配置。
 */
@EnabledIfEnvironmentVariable(named = "NOON_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlSiteAuthorizationIT extends SiteAuthorizationTest
{
    private static final ThreadLocal<String> DATABASE = new ThreadLocal<>();

    @Test
    void usesActualMySqlRepeatableRead()
    {
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("select @@transaction_isolation", String.class));
    }

    @Test
    void waitingReplacementReadsCommittedGrantInsteadOfOldSnapshot() throws Exception
    {
        service.replaceUserSites(2L, List.of("AE"));
        var pool = Executors.newSingleThreadExecutor();
        try (var blocker = context.getBean(DataSource.class).getConnection())
        {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("select user_id from sys_user where user_id=2 for update"))
            {
                lock.executeQuery().close();
            }
            try (var update = blocker.prepareStatement("update sys_user_site set site_code='SA' where user_id=2"))
            {
                update.executeUpdate();
            }
            // Service 先建立 RR 快照再等用户行锁；此时快照仍为旧 AE。
            var replacement = pool.submit(() -> {
                login(1L);
                try { service.replaceUserSites(2L, List.of("AE")); }
                finally { SecurityContextHolder.clearContext(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline)
            {
                waiting = jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits", Integer.class) > 0;
                if (waiting) break;
                Thread.sleep(10);
            }
            assertTrue(waiting, "须观察到真实锁等待后才释放，不能靠线程时间碰运气");
            blocker.commit();
            replacement.get(10, TimeUnit.SECONDS);
            assertEquals(List.of("AE"), service.getUserSites(2L));
            var audit = jdbc.queryForMap("select before_codes, after_codes from sys_user_site_audit order by id desc limit 1");
            assertEquals("SA", audit.get("before_codes"));
            assertEquals("AE", audit.get("after_codes"));
        }
        finally { pool.shutdownNow(); }
    }

    @Override
    protected Class<?> configClass() { return MySqlConfig.class; }

    @Override
    protected String migrationSql(String original) { return original; }

    @Override
    protected void cleanDatabase()
    {
        String database = DATABASE.get();
        if (database != null)
        {
            new JdbcTemplate(serverDataSource()).execute("drop database `" + database + "`");
            DATABASE.remove();
        }
    }

    private static DriverManagerDataSource serverDataSource()
    {
        int port = Integer.parseInt(System.getenv("NOON_MYSQL_TEST_PORT"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("测试端口无效");
        String password = System.getenv("NOON_MYSQL_TEST_PASSWORD");
        if (password == null || password.isEmpty()) throw new IllegalStateException("仅接受独立测试实例凭据");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai", "root", password);
    }

    @Configuration
    static class MySqlConfig extends Config
    {
        @Bean
        @Override
        DataSource dataSource()
        {
            DriverManagerDataSource ds = serverDataSource();
            String database = "noon_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            DATABASE.set(database);
            ds.setUrl(ds.getUrl().replace("/?", "/" + database + "?"));
            return ds;
        }
    }
}
