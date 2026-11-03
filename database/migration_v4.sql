-- ============================================================================
-- V4.0 数据库变更脚本
-- 项目：实验室开放预约与使用记录管理系统
-- 依据：《需求设计文档.md》V4.0 第 2.3 节表结构定义
-- 适用：在底版 database/init_db.sql 已执行完毕的库上增量执行
-- 幂等性：建表用 IF NOT EXISTS；加列前先查 information_schema 避免重复执行报错
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 reservation < database/migration_v4.sql
--       目标库由命令行参数指定，脚本内不写死 USE，避免误作用到其他库
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 工具：若列不存在则添加（MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_absent;
DELIMITER $$
CREATE PROCEDURE add_column_if_absent(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl    VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------------
-- 1. sys_user：role 增加教师（仅注释变更，无字段增删）
--    0-学生，1-实验室管理员，2-教师
-- ---------------------------------------------------------------------------
ALTER TABLE sys_user
    MODIFY COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '角色：0-学生，1-实验室管理员，2-教师';

-- ---------------------------------------------------------------------------
-- 2. classroom：新增开放要求字段
-- ---------------------------------------------------------------------------
CALL add_column_if_absent('classroom', 'open_requirement',
    "open_requirement VARCHAR(255) NULL COMMENT '开放要求/准入门槛' AFTER equipment");

-- ---------------------------------------------------------------------------
-- 3. 新建表：lab_open_slot 开放时段
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lab_open_slot (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    classroom_id  BIGINT       NOT NULL                COMMENT '所属实验室 ID',
    start_date    DATE         NOT NULL                COMMENT '开放起始日期',
    end_date      DATE         NOT NULL                COMMENT '开放结束日期',
    week_days     VARCHAR(20)  NOT NULL                COMMENT '可预约星期，如 1,2,3,4,5（1-7 周一至周日）',
    open_time     TIME         NOT NULL                COMMENT '开放时段开始时间',
    close_time    TIME         NOT NULL                COMMENT '开放时段结束时间',
    slot_capacity INT          NOT NULL                COMMENT '该时段预约人数上限',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：0-停用，1-启用',
    remark        VARCHAR(255) NULL                    COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_class_date (classroom_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实验室开放时段表';

-- ---------------------------------------------------------------------------
-- 4. reservation：新增 people_count / cancel_reason / cancel_time
-- ---------------------------------------------------------------------------
CALL add_column_if_absent('reservation', 'people_count',
    "people_count INT NOT NULL DEFAULT 1 COMMENT '预约使用人数（容量控制依据）' AFTER end_time");

CALL add_column_if_absent('reservation', 'cancel_reason',
    "cancel_reason VARCHAR(255) NULL COMMENT '取消原因' AFTER audit_time");

CALL add_column_if_absent('reservation', 'cancel_time',
    "cancel_time DATETIME NULL COMMENT '取消时间' AFTER cancel_reason");

-- reservation 增加我的预约筛选索引
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reservation'
               AND INDEX_NAME = 'idx_user_status');
SET @ddl := IF(@idx = 0,
    'ALTER TABLE reservation ADD INDEX idx_user_status (user_id, status)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5. 新建表：usage_record 使用记录
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS usage_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    reservation_id BIGINT       NOT NULL                COMMENT '关联预约 ID（一条预约仅一条登记）',
    classroom_id   BIGINT       NOT NULL                COMMENT '实验室 ID（冗余，便于统计）',
    user_id        BIGINT       NOT NULL                COMMENT '使用用户 ID（冗余）',
    use_date       DATE         NOT NULL                COMMENT '使用日期（冗余，便于按日统计）',
    actual_people  INT          NOT NULL DEFAULT 0      COMMENT '实际使用人数',
    use_status     TINYINT      NOT NULL                COMMENT '使用状态：1-正常使用，2-部分使用，3-失约',
    check_remark   VARCHAR(255) NULL                    COMMENT '登记备注',
    registrar_id   BIGINT       NOT NULL                COMMENT '登记人（管理员）ID',
    check_time     DATETIME     NOT NULL                COMMENT '登记时间',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (reservation_id),
    KEY idx_date_class (use_date, classroom_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实验室使用记录表';

-- ---------------------------------------------------------------------------
-- 6. 清理临时存储过程
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_absent;

-- ---------------------------------------------------------------------------
-- 7. 初始化开放时段示例数据（可选，答辩演示用）
--    为全部在用实验室配置「工作日 08:00-12:00 / 14:00-18:00」两个时段
--    人数上限取 min(教室容量, 60)，保证 slot_capacity 不超过 classroom.capacity
--    注意：不使用 LIMIT，否则教室数量超过 10 间时会有实验室漏配时段，
--          按规则 3「无时段配置的实验室不可预约」，漏配将导致该实验室无法被预约
-- ---------------------------------------------------------------------------
INSERT INTO lab_open_slot (classroom_id, start_date, end_date, week_days, open_time, close_time, slot_capacity, status, remark)
SELECT c.id, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), '1,2,3,4,5', '08:00:00', '12:00:00', LEAST(c.capacity, 60), 1, '工作日上午时段'
FROM classroom c
WHERE NOT EXISTS (
    SELECT 1 FROM lab_open_slot s WHERE s.classroom_id = c.id AND s.open_time = '08:00:00'
);

INSERT INTO lab_open_slot (classroom_id, start_date, end_date, week_days, open_time, close_time, slot_capacity, status, remark)
SELECT c.id, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 90 DAY), '1,2,3,4,5', '14:00:00', '18:00:00', LEAST(c.capacity, 60), 1, '工作日下午时段'
FROM classroom c
WHERE NOT EXISTS (
    SELECT 1 FROM lab_open_slot s WHERE s.classroom_id = c.id AND s.open_time = '14:00:00'
);
