package com.yx.framework.security.site;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import com.yx.framework.configversion.MyBatisRuleConfigRepository;
import com.yx.framework.configversion.VersionedConfigReader;
import com.yx.system.mapper.RuleConfigHistoryMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 在已有独立 MySQL 测试 fixture 中执行 v0.5 原始参数表 DDL。 */
class MySqlConfigHistoryIT extends MySqlSiteAuthorizationIT
{
    @Test
    void realMapperReadsRevokedHistoryAndChecksSiteBeforeQuery() throws Exception
    {
        String sql = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        String table = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS noon_rule_config ("));
        jdbc.execute(table.substring(0, table.indexOf(';') + 1));
        jdbc.update("insert into noon_rule_config(config_key,scope,config_value,version_no,effective_from,revoked_at,status,created_by) values "
                + "('days','GLOBAL','14',1,'2026-11-01',null,1,1),"
                + "('days','AE','15',1,'2026-11-01','2026-11-10',0,1),"
                + "('days','SA','99',1,'2026-11-01',null,1,1)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(context.getBean(DataSource.class));
        factory.setMapperLocations(new ClassPathResource("mapper/system/RuleConfigHistoryMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(RuleConfigHistoryMapper.class);
        var repo = new MyBatisRuleConfigRepository(mapper);
        var reader = new VersionedConfigReader(context.getBean(SiteAuthGuard.class));
        var cutoff = LocalDateTime.parse("2026-11-10T00:00:00");
        assertEquals(2, repo.findHistory("days", "AE").size());
        var original = reader.resolve(repo, "days", "AE", cutoff.minusNanos(1));
        assertEquals("15", original.value());
        assertEquals("14", reader.resolve(repo, "days", "AE", cutoff).value());
        jdbc.update("update noon_rule_config set status=0, revoked_at='2026-11-02' where scope='GLOBAL'");
        assertEquals("15", reader.valueFromSnapshot(original));
        login(2L);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> reader.resolve(repo, "days", "AE", cutoff));
        login(1L);
        service.replaceUserSites(2L, java.util.List.of("AE"));
        login(2L);
        assertEquals("15", reader.resolve(repo, "days", "AE", cutoff.minusNanos(1)).value());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> reader.resolve(repo, "days", "SA", cutoff));
    }
}
