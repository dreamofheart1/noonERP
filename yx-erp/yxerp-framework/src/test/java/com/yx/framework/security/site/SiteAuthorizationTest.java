package com.yx.framework.security.site;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.common.exception.ServiceException;
import com.yx.framework.web.service.SysUserSiteService;
import com.yx.system.mapper.SysUserSiteMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真 MyBatis XML + H2 MySQL 模式 + Spring 事务；不启动 Redis 或生产连接。 */
class SiteAuthorizationTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SiteAuthGuard guard;
    private SysUserSiteService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        // 运行生产迁移，仅移除 H2 不支持的 MySQL 字符集/排序规则声明。
        String migration = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"))
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "");
        for (String statement : migration.split(";"))
        {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0'), (3,'0','0'), (4,'1','0'), (5,'0','2')");
        guard = context.getBean(SiteAuthGuard.class);
        service = context.getBean(SysUserSiteService.class);
        login(1L);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void administratorHasEverySupportedSiteWithoutGrantRows()
    {
        assertEquals(List.of("AE", "SA"), guard.allowedSiteCodes());
        assertEquals("SA", guard.requireSite("SA"));
        assertEquals(0, count("sys_user_site"));
    }

    @Test
    void emptyAuthorizationDeniesAllSites()
    {
        login(2L);
        assertEquals(List.of(), guard.allowedSiteCodes());
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("AE"));
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("SA"));
    }

    @Test
    void explicitGrantIsRequiredAndRevocationAffectsSameLogin()
    {
        service.replaceUserSites(2L, List.of("AE"));
        login(2L);
        var existingAuthentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("AE", guard.requireSite("AE"));
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("SA"));
        login(1L);
        service.replaceUserSites(2L, List.of());
        SecurityContextHolder.getContext().setAuthentication(existingAuthentication);
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("AE"));
    }

    @Test
    void noAuthenticationOrAnonymousOrUnauthenticatedPrincipalIsDenied()
    {
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("AE"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", "", List.of()));
        assertThrows(AccessDeniedException.class, guard::allowedSiteCodes);
        login(2L);
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, ""));
        assertThrows(AccessDeniedException.class, guard::allowedSiteCodes);
    }

    @Test
    void inconsistentIdentityCannotImpersonateAdmin()
    {
        login(2L);
        LoginUser principal = (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        principal.setUserId(1L);
        assertThrows(AccessDeniedException.class, guard::requireAdministrator);
    }

    @Test
    void deletedDisabledAndNonexistentUsersDeniedEvenWithOldPrincipal()
    {
        for (Long id : List.of(4L, 5L, 999L))
        {
            login(id);
            assertThrows(AccessDeniedException.class, guard::allowedSiteCodes);
        }
        login(1L);
        jdbc.update("update sys_user set status='1' where user_id=1");
        assertThrows(AccessDeniedException.class, guard::requireAdministrator);
    }

    @Test
    void nonAdminCannotReadOrChangeAnyUsersGrantsIncludingOwn()
    {
        login(2L);
        assertThrows(AccessDeniedException.class, () -> service.getUserSites(3L));
        assertThrows(AccessDeniedException.class, () -> service.replaceUserSites(2L, List.of("SA")));
        assertEquals(0, count("sys_user_site"));
    }

    @Test
    void wildcardRoleAndMenuPermissionNeverGrantSites()
    {
        login(2L); // login() 包含菜单 *:*:*；站点仍须独立授权
        assertThrows(AccessDeniedException.class, () -> guard.requireSite("AE"));
        for (String code : Arrays.asList(null, "", "*", "ae", "AE ", "AE' OR 1=1 --", "GLOBAL"))
        {
            assertThrows(AccessDeniedException.class, () -> guard.requireSite(code));
        }
    }

    @Test
    void replacementValidatesEntireInputBeforeDeletingOldGrants()
    {
        service.replaceUserSites(2L, List.of("AE"));
        for (List<String> invalid : Arrays.asList(null, List.of("*"), List.of("SA", "XX"),
                Arrays.asList((String) null), List.of("AE", "SA", "AE")))
        {
            assertThrows(ServiceException.class, () -> service.replaceUserSites(2L, invalid));
            assertEquals(List.of("AE"), service.getUserSites(2L));
        }
    }

    @Test
    void administratorCannotBeOverriddenAndInvalidTargetsRejected()
    {
        for (Long id : Arrays.asList(null, 0L, -1L, 1L, 4L, 5L, 999L))
        {
            assertThrows(ServiceException.class, () -> service.replaceUserSites(id, List.of("AE")));
        }
        assertEquals(0, count("sys_user_site_audit"));
    }

    @Test
    void idempotentSortedReplacementRecordsActorAndBeforeAfter()
    {
        service.replaceUserSites(2L, List.of("SA", "AE"));
        service.replaceUserSites(2L, List.of("AE", "SA"));
        assertEquals(List.of("AE", "SA"), service.getUserSites(2L));
        assertEquals(1, count("sys_user_site_audit"));
        service.replaceUserSites(2L, List.of("SA"));
        var audit = jdbc.queryForMap("select * from sys_user_site_audit order by id desc limit 1");
        assertEquals(1L, ((Number) audit.get("ACTOR_ID")).longValue());
        assertEquals("AE,SA", audit.get("BEFORE_CODES"));
        assertEquals("SA", audit.get("AFTER_CODES"));
        assertNotNull(audit.get("CREATE_TIME"));
    }

    @Test
    void auditFailureRollsBackDeletionAndInsertion()
    {
        service.replaceUserSites(2L, List.of("AE"));
        jdbc.execute("alter table sys_user_site_audit add constraint reject_sa check (after_codes <> 'SA')");
        assertThrows(RuntimeException.class, () -> service.replaceUserSites(2L, List.of("SA")));
        assertEquals(List.of("AE"), service.getUserSites(2L));
        assertEquals(1, count("sys_user_site_audit"));
    }

    @Test
    void concurrentReplacementIsSerializedWithoutCombiningGrants() throws Exception
    {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try
        {
            var futures = List.of("AE", "SA").stream().map(code -> pool.submit(() -> {
                login(1L);
                try
                {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    service.replaceUserSites(2L, List.of(code));
                }
                finally { SecurityContextHolder.clearContext(); }
                return code;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, service.getUserSites(2L).size());
            assertEquals(2, count("sys_user_site_audit"));
            var before = jdbc.queryForList("select before_codes from sys_user_site_audit order by id", String.class);
            assertEquals("", before.get(0));
            assertTrue(List.of("AE", "SA").contains(before.get(1)));
        }
        finally { pool.shutdownNow(); }
    }

    @Test
    void databaseEnforcesUniqueGrantsAndExistingUser()
    {
        service.replaceUserSites(2L, List.of("AE"));
        var mapper = context.getBean(SysUserSiteMapper.class);
        var now = java.time.LocalDateTime.now();
        assertThrows(RuntimeException.class, () -> mapper.insertGrant(2L, "AE", 1L, now));
        assertThrows(RuntimeException.class, () -> mapper.insertGrant(999L, "AE", 1L, now));
    }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void login(Long id)
    {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName("user" + id);
        LoginUser principal = new LoginUser(id, 100L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", List.of()));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config
    {
        @Bean
        DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }
        @Bean
        PlatformTransactionManager transactionManager(DataSource ds)
        {
            return new DataSourceTransactionManager(ds);
        }
        @Bean
        SysUserSiteMapper mapper(DataSource ds) throws Exception
        {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserSiteMapper.xml"));
            return new SqlSessionTemplate(factory.getObject()).getMapper(SysUserSiteMapper.class);
        }
        @Bean
        SiteAuthGuard guard(SysUserSiteMapper mapper) { return new SiteAuthGuard(mapper); }
        @Bean
        SysUserSiteService service(SysUserSiteMapper mapper, SiteAuthGuard guard)
        {
            return new SysUserSiteService(mapper, guard);
        }
    }
}
