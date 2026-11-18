-- WP1 增量迁移：在若依 sys_user 已初始化的隔离/开发库执行一次。
-- 全新安装：先若依基础 SQL，再本文件；已有若依库：仅本文件。
-- 不执行旧 Noon schema 中冲突的 sys_user/sys_config DDL；无自动数据推断/授权。
-- 故意不使用 IF NOT EXISTS：已存在同名表时失败，先核对结构，不能静默掩盖差异。
-- MySQL DDL 非事务；执行前备份并检查两表均不存在。审计时间由应用显式传北京时间。
CREATE TABLE sys_user_site (
    user_id BIGINT NOT NULL,
    site_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, site_code),
    CONSTRAINT fk_user_site_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='若依用户站点授权（空授权拒绝）';

CREATE TABLE sys_user_site_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    before_codes VARCHAR(255) NOT NULL,
    after_codes VARCHAR(255) NOT NULL,
    create_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_site_audit (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点授权变更审计（与授权同事务）';
