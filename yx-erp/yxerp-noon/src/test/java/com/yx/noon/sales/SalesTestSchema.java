package com.yx.noon.sales;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;

/**
 * M5 H2 夹具（只在测试包内使用）：按最小可用结构建表，列与生产 SQL 一致（CHECK/唯一键的
 * 真实性由 {@code MySqlSalesIT} 用 schema v0.5 原 DDL 验证）。
 *
 * <p>刻意不使用 schema v0.5 原文：其中 MySQL 专属（ENGINE/COMMENT/生成列）H2 不兼容；
 * 与既有 intake/fifo/reversal H2 测试同口径手工建表。
 */
final class SalesTestSchema
{
    private SalesTestSchema()
    {
    }

    static void init(JdbcTemplate jdbc)
    {
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        try
        {
            String siteMigration = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"));
            for (String statement : h2Ddl(siteMigration).split(";"))
                if (!statement.isBlank()) jdbc.execute(statement);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("站点授权迁移读取失败", e);
        }

        // 主数据（IntakeService 校验 store→site、sku、partner_sku 映射）
        jdbc.execute("create table md_store (id bigint auto_increment primary key, store_no varchar(64) not null, "
                + "partner_id bigint not null, status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_site (id bigint auto_increment primary key, site_code varchar(16) not null, "
                + "store_id bigint not null, currency char(3) not null, status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, "
                + "partner_sku varchar(128) not null, sku_id bigint not null, site_code varchar(16) not null, "
                + "business_model varchar(24) not null default 'noon', offer_code varchar(128), barcode varchar(128), "
                + "noon_sku varchar(128), image_url varchar(512), live_status boolean, offer_issues varchar(4000), synced_at timestamp(3), "
                + "status varchar(16) not null default 'ACTIVE')");

        // 系统侧事件留痕
        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw varchar(4000), received_at timestamp not null, processed_at timestamp, "
                + "status varchar(24) not null, unique key(event_id))");

        // 订单/订单行/退货
        jdbc.execute("create table sal_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "site_code varchar(16) not null, store_id bigint not null, partner_sku varchar(128) not null, "
                + "sku_id bigint, qty int not null, gross_amount decimal(18,4) not null, currency char(3) not null, "
                + "source_channel varchar(24), import_fingerprint varchar(64), status varchar(32), "
                + "state_version int not null default 1, raw_payload varchar(4000), "
                + "created_at timestamp, updated_at timestamp, unique key(order_no, site_code))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, "
                + "sal_order_id bigint not null, order_no varchar(64) not null, platform_item_no varchar(64), "
                + "sku_id bigint not null, qty int not null, qty_delivered int not null default 0, "
                + "qty_returned int not null default 0, qty_cancelled int not null default 0, "
                + "unit_price decimal(18,4) not null, created_at timestamp)");
        jdbc.execute("create table sal_return (id bigint auto_increment primary key, return_no varchar(64) not null, "
                + "biz_return_key varchar(160) not null, source_return_no varchar(128), order_no varchar(64) not null, "
                + "site_code varchar(16), sku_id bigint not null, qty int not null, sal_order_item_id bigint, "
                + "status varchar(24) not null default 'OPEN', grade varchar(8), "
                + "compensation_amount decimal(18,4) default 0, refund_amount decimal(18,4) default 0, "
                + "return_loss_amount decimal(18,4) default 0, restock_batch_no varchar(64), period char(7), "
                + "original_period char(7), raw_payload varchar(4000), created_at timestamp, "
                + "unique key(biz_return_key), unique key(site_code,order_no,source_return_no))");

        // 库存批次/台账/消耗流水
        jdbc.execute("create table inv_batch (id bigint auto_increment primary key, batch_no varchar(64) not null, "
                + "sku_id bigint not null, pur_order_item_id bigint, warehouse_id bigint not null, "
                + "site_code varchar(16) not null, received_at timestamp not null, qty_in int not null, "
                + "qty_remaining int not null, unit_cost decimal(18,4) not null, currency char(3) not null default 'CNY', "
                + "fx_rate decimal(18,8), source_type varchar(24) not null default 'PURCHASE', "
                + "parent_batch_id bigint, split_seq int not null default 0, split_reason varchar(24), "
                + "transfer_no varchar(64), asn_no varchar(64), status varchar(16) not null default 'OPEN', "
                + "created_at timestamp, unique key(batch_no))");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint not null, "
                + "warehouse_id bigint not null, site_code varchar(16) not null, "
                + "qty_available int not null default 0, qty_in_transit int not null default 0, "
                + "qty_reserved int not null default 0, qty_unsellable int not null default 0, "
                + "updated_at timestamp, unique key(sku_id,warehouse_id))");
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null, biz_event_key varchar(160) not null, "
                + "source_event_id varchar(128), batch_id bigint not null, sal_order_item_id bigint, "
                + "sku_id bigint not null, qty int not null, unit_cost decimal(18,4) not null, "
                + "amount decimal(18,4) not null, consumed_at timestamp not null, move_type varchar(24) not null, "
                + "sign int not null, reversal_of_id bigint, sal_return_id bigint, "
                + "refund_amount decimal(18,4) default 0, period char(7), cross_period int not null default 0, "
                + "raw_payload varchar(4000), created_at timestamp not null, "
                + "unique key(consumption_no), unique key(biz_event_key,batch_id))");

        // 内部服务隔离层与审计（列与各自 SQL 文件一致）
        jdbc.execute("create table noon_intake_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), order_no_claimed varchar(64), source_return_no varchar(128), "
                + "source_event_id varchar(128), raw_payload varchar(4000), reason_code varchar(48) not null, "
                + "reason_detail varchar(512), status varchar(24) not null default 'NEEDS_REVIEW', "
                + "resolution_biz_key varchar(160), resolved_by bigint, resolved_at timestamp, "
                + "created_by bigint, created_at timestamp not null default current_timestamp, "
                + "unique key(review_no))");
        jdbc.execute("create table noon_intake_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");
        jdbc.execute("create table noon_fifo_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), sal_order_item_id_claimed bigint, source_event_id varchar(128), "
                + "raw_payload varchar(4000), reason_code varchar(48) not null, reason_detail varchar(512), "
                + "status varchar(24) not null default 'NEEDS_REVIEW', created_by bigint, "
                + "created_at timestamp not null default current_timestamp, unique key(review_no))");
        jdbc.execute("create table noon_fifo_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");
        jdbc.execute("create table noon_reversal_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), sal_return_id_claimed bigint, "
                + "original_consumption_id_claimed bigint, source_event_id varchar(128), "
                + "raw_payload varchar(4000), reason_code varchar(48) not null, reason_detail varchar(512), "
                + "status varchar(24) not null default 'NEEDS_REVIEW', resolution_biz_key varchar(160), "
                + "resolved_by bigint, resolved_at timestamp, created_by bigint, "
                + "created_at timestamp not null default current_timestamp, unique key(review_no))");
        jdbc.execute("create table noon_reversal_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");

        // M5 动作级幂等表（与 yx-erp/sql/noon_m5_sales.sql 同结构，JSON 列退化为 varchar）
        jdbc.execute("create table noon_m5_sales_request (request_id varchar(64) not null, "
                + "action varchar(24) not null, entity_id bigint not null default 0, site_code varchar(16) not null, "
                + "operator_id bigint, content_fingerprint char(64) not null, status varchar(16) not null default 'PENDING', "
                + "result_json varchar(4000), created_at timestamp not null default current_timestamp, "
                + "primary key(request_id, action))");

        // M4 库存变更审计（复用，不新增 DDL）
        jdbc.execute("create table noon_m4_inventory_audit (id bigint auto_increment primary key, "
                + "biz_type varchar(32) not null, biz_id varchar(64), action varchar(32) not null, "
                + "detail varchar(1024), operator_id bigint, created_at timestamp not null default current_timestamp)");
    }

    /** 主数据：store 7 归属 AE/SA，SKU 11，Partner SKU 'SKU' 在 AE/SA 均 ACTIVE。 */
    static void seedMasterData(JdbcTemplate jdbc)
    {
        jdbc.update("insert into md_store(id,store_no,partner_id,status) values (7,'STORE-AE',1,'ACTIVE')");
        jdbc.update("insert into md_site(id,site_code,store_id,currency,status) values "
                + "(1,'AE',7,'AED','ACTIVE'),(2,'SA',7,'SAR','ACTIVE')");
        jdbc.update("insert into md_sku(id,sku_code,status) values (11,'SKU-11','ACTIVE')");
        jdbc.update("insert into md_partner_sku(id,partner_sku,sku_id,site_code,business_model,status) values "
                + "(1,'SKU',11,'AE','noon','ACTIVE'),(2,'SKU',11,'SA','noon','ACTIVE')");
    }

    static void login(long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("user" + userId);
        LoginUser principal = new LoginUser(userId, 100L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", List.of()));
    }

    static void grant(JdbcTemplate jdbc, long userId, String... sites)
    {
        for (String site : sites)
            jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) "
                    + "values (?,?,1,current_timestamp)", userId, site);
    }

    static void clearAuth()
    {
        SecurityContextHolder.clearContext();
    }

    private static String h2Ddl(String sql)
    {
        return sql.replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replaceAll("COMMENT\\s*=\\s*'[^']*'", "");
    }
}
