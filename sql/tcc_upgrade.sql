-- ==========================================
-- 1. 为现有的 t_storage 追加冻结库存字段
-- ==========================================
USE `storage_db`;
ALTER TABLE `t_storage` ADD COLUMN `frozen` int(11) DEFAULT 0 COMMENT '冻结库存';

-- ==========================================
-- 2. 官方精配的 TCC 防悬挂与幂等性控制表 tcc_fence_log
-- ==========================================
CREATE TABLE IF NOT EXISTS `tcc_fence_log`
(
    `xid`           VARCHAR(128)  NOT NULL COMMENT 'global id',
    `branch_id`     BIGINT        NOT NULL COMMENT 'branch id',
    `action_name`   VARCHAR(64)   NOT NULL COMMENT 'action name',
    `status`        TINYINT       NOT NULL COMMENT 'status(tried:1;committed:2;rollbacked:3;suspended:4)',
    `gmt_create`    DATETIME(3)   NOT NULL COMMENT 'create time',
    `gmt_modified`  DATETIME(3)   NOT NULL COMMENT 'update time',
    PRIMARY KEY (`xid`, `branch_id`),
    KEY `idx_gmt_modified` (`gmt_modified`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'TCC fence log';
