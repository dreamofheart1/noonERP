package com.yx.noon.sales;

import java.util.UUID;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.fifo.FifoConsumptionService;
import com.yx.noon.intake.IntakeService;
import com.yx.noon.inventory.InventoryAudit;
import com.yx.noon.reversal.ReversalService;
import com.yx.system.mapper.SysUserSiteMapper;

/**
 * M5 H2 测试上下文：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper + 真 {@link SiteAuthGuard}。
 * 不启动 MySQL/Redis/生产连接。所有内部闸门（intake/fifo/reversal）用其真实实现，未做 mock，
 * 保证编排层验证的是跨服务真实契约。
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
class SalesTestConfig
{
    @Bean
    DataSource dataSource()
    {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sales-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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
    JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }

    @Bean
    InventoryAudit inventoryAudit(JdbcTemplate jdbc) { return new InventoryAudit(jdbc); }

    @Bean
    IntakeService intakeService(JdbcTemplate jdbc, SiteAuthGuard guard) { return new IntakeService(jdbc, guard); }

    @Bean
    FifoConsumptionService fifoConsumptionService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        return new FifoConsumptionService(jdbc, guard);
    }

    @Bean
    ReversalService reversalService(JdbcTemplate jdbc, SiteAuthGuard guard) { return new ReversalService(jdbc, guard); }

    @Bean
    ReturnDispositionService returnDispositionService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        return new ReturnDispositionService(jdbc, guard, audit);
    }

    @Bean
    SalesFlowService salesFlowService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit,
                                      IntakeService intake, FifoConsumptionService fifo,
                                      ReversalService reversal, ReturnDispositionService disposition)
    {
        return new SalesFlowService(jdbc, guard, audit, intake, fifo, reversal, disposition);
    }
}
