-- =====================================================================
-- 高校实验室预约管理系统 - 数据库初始化脚本（V4.0 基线）
-- 依据：需求设计文档 V4.0 第 2.3 节（表结构）/ spec.md 第 4 节（建库建表与测试数据）
-- 版本：V4.0 —— 新增三角色（学生/教师/实验室管理员）、实验室开放时段、容量控制与使用登记
-- 数据库：reservation（utf8mb4）
-- 说明：测试数据中"当日/本周/本月"基于 CURDATE() 动态生成，任意日期导入均可支撑
--       日历、看板与登录提醒演示；密码均为 BCrypt 加密存储（admin/admin123，学生 123456，教师 teacher/123）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS reservation DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE reservation;

-- ---------------------------------------------------------------------
-- 1. 用户表 sys_user
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    username    VARCHAR(32)  NOT NULL COMMENT '登录账号',
    password    VARCHAR(64)  NOT NULL COMMENT '密码（BCrypt加密）',
    name        VARCHAR(20)  NOT NULL COMMENT '真实姓名',
    student_no  VARCHAR(20)  NULL COMMENT '学号（学生角色必填）',
    phone       VARCHAR(11)  NULL COMMENT '联系电话',
    email       VARCHAR(50)  NULL COMMENT '邮箱',
    role        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0-学生，1-实验室管理员，2-教师',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 2. 教室表 classroom
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS classroom;
CREATE TABLE classroom (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name        VARCHAR(50)  NOT NULL COMMENT '教室名称',
    building    VARCHAR(30)  NOT NULL COMMENT '所属楼栋',
    room_no     VARCHAR(20)  NOT NULL COMMENT '教室编号',
    type        TINYINT      NOT NULL COMMENT '类型：1-普通教室，2-实验室，3-机房',
    capacity    INT          NOT NULL COMMENT '容纳人数',
    equipment   TEXT         NULL COMMENT '设备说明',
    description VARCHAR(255) NULL COMMENT '备注描述',
    open_requirement VARCHAR(255) NULL COMMENT '开放要求（如穿实验服、需指导教师在场、禁止饮食等）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-可用',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '教室表';

-- ---------------------------------------------------------------------
-- 3. 预约表 reservation
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS reservation;
CREATE TABLE reservation (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id      BIGINT       NOT NULL COMMENT '预约用户ID',
    classroom_id BIGINT       NOT NULL COMMENT '预约教室ID',
    reserve_date DATE         NOT NULL COMMENT '预约日期',
    start_time   TIME         NOT NULL COMMENT '开始时间',
    end_time     TIME         NOT NULL COMMENT '结束时间',
    people_count INT          NOT NULL DEFAULT 1 COMMENT '本次预约使用人数（用于容量控制）',
    purpose      VARCHAR(255) NOT NULL COMMENT '预约用途',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-待审核，1-已通过，2-已驳回，3-已取消',
    audit_remark VARCHAR(255) NULL COMMENT '审核备注',
    auditor_id   BIGINT       NULL COMMENT '审核人ID',
    audit_time   DATETIME     NULL COMMENT '审核时间',
    cancel_reason VARCHAR(255) NULL COMMENT '取消原因（用户主动取消时填写）',
    cancel_time  DATETIME     NULL COMMENT '取消时间',
    create_time  DATETIME     NOT NULL COMMENT '创建时间',
    update_time  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_classroom_id (classroom_id),
    KEY idx_reserve_date (reserve_date),
    KEY idx_user_status (user_id, status) COMMENT '个人预约列表按状态过滤',
    KEY idx_class_date (classroom_id, reserve_date) COMMENT '冲突检测优化联合索引'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '预约表';

-- ---------------------------------------------------------------------
-- 4. 用户收藏表 user_favorite
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS user_favorite;
CREATE TABLE user_favorite (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id      BIGINT   NOT NULL COMMENT '用户ID',
    classroom_id BIGINT   NOT NULL COMMENT '教室ID',
    create_time  DATETIME NOT NULL COMMENT '收藏时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_classroom_id (classroom_id),
    UNIQUE KEY idx_user_class (user_id, classroom_id) COMMENT '防止重复收藏'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户收藏表';

-- ---------------------------------------------------------------------
-- 5. AI 配置表 ai_config
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS ai_config;
CREATE TABLE ai_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    config_key   VARCHAR(50)  NOT NULL COMMENT '配置键',
    config_value TEXT         NOT NULL COMMENT '配置值',
    description  VARCHAR(255) NULL COMMENT '配置说明',
    create_time  DATETIME     NOT NULL COMMENT '创建时间',
    update_time  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI配置表';

-- ---------------------------------------------------------------------
-- 6. 实验室开放时段表 lab_open_slot
-- 定义某实验室在 [start_date, end_date] 区间内、按 week_days 指定的星期几开放，
-- 且每天在 (open_time, close_time) 窗口内可被预约，同时限制该时段的预约人数上限。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS lab_open_slot;
CREATE TABLE lab_open_slot (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    classroom_id  BIGINT      NOT NULL COMMENT '实验室ID',
    start_date    DATE        NOT NULL COMMENT '开放起始日期',
    end_date      DATE        NOT NULL COMMENT '开放结束日期',
    week_days     VARCHAR(20) NOT NULL COMMENT '开放星期（1-7 逗号分隔，1=周一）',
    open_time     TIME        NOT NULL COMMENT '开放开始时间',
    close_time    TIME        NOT NULL COMMENT '开放结束时间',
    slot_capacity INT         NOT NULL COMMENT '该时段预约人数上限（不大于实验室容量）',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-启用',
    remark        VARCHAR(255) NULL COMMENT '备注',
    create_time   DATETIME    NOT NULL COMMENT '创建时间',
    update_time   DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_class_date (classroom_id, start_date, end_date) COMMENT '按实验室与日期区间查询时段'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '实验室开放时段表';

-- ---------------------------------------------------------------------
-- 7. 使用记录表 usage_record
-- 预约审核通过后，由管理员在管理员登记实际使用情况；一个预约只能登记一次。
-- use_status：1-正常使用 / 2-部分使用（实际人数少于预约人数）/ 3-失约（实际人数为 0）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS usage_record;
CREATE TABLE usage_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    reservation_id BIGINT       NOT NULL COMMENT '预约ID（一个预约仅可登记一次）',
    classroom_id   BIGINT       NOT NULL COMMENT '实验室ID（冗余，便于统计）',
    user_id        BIGINT       NOT NULL COMMENT '预约用户ID（冗余，便于统计）',
    use_date       DATE         NOT NULL COMMENT '使用日期',
    actual_people  INT          NOT NULL COMMENT '实际使用人数',
    use_status     TINYINT      NOT NULL COMMENT '使用状态：1-正常，2-部分使用，3-失约',
    check_remark   VARCHAR(255) NULL COMMENT '登记备注',
    registrar_id   BIGINT       NOT NULL COMMENT '登记人ID（管理员）',
    check_time     DATETIME     NOT NULL COMMENT '登记时间',
    create_time    DATETIME     NOT NULL COMMENT '创建时间',
    update_time    DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (reservation_id) COMMENT '防止重复登记',
    KEY idx_class_date (classroom_id, use_date) COMMENT '按实验室与日期统计',
    KEY idx_use_date (use_date) COMMENT '使用率与热门时段统计'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '使用记录表';

-- =====================================================================
-- 初始测试数据（spec.md 4.3）
-- =====================================================================

-- 管理员 1 个（admin/admin123）+ 学生 4 个（密码统一 123456）+ 教师 1 个（teacher/123），BCrypt 加密
INSERT INTO sys_user (id, username, password, name, student_no, phone, email, role, status, create_time, update_time) VALUES
(1, 'admin',    '$2b$10$7BOU7bPpg.CyyPtacaN5oOYxwA5w6Hzost.bAKwhxfJlJw/LRaTh.', '系统管理员', NULL,     '13800000000', 'admin@reservation.edu.cn',    1, 1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(2, 'zhangsan', '$2b$10$L76Rs6hyfZgvU/4x8Gy/z.8YLRJFv4sJo3Jk8MDXqIFkRPxoLy16a', '张三', '2024001', '13800000001', 'zhangsan@stu.reservation.edu.cn', 0, 1, NOW() - INTERVAL 28 DAY, NOW() - INTERVAL 28 DAY),
(3, 'lisi',     '$2b$10$L76Rs6hyfZgvU/4x8Gy/z.8YLRJFv4sJo3Jk8MDXqIFkRPxoLy16a', '李四', '2024002', '13800000002', 'lisi@stu.reservation.edu.cn',     0, 1, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 25 DAY),
(4, 'wangwu',   '$2b$10$L76Rs6hyfZgvU/4x8Gy/z.8YLRJFv4sJo3Jk8MDXqIFkRPxoLy16a', '王五', '2024003', '13800000003', 'wangwu@stu.reservation.edu.cn',   0, 1, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY),
(5, 'zhaoliu',  '$2b$10$L76Rs6hyfZgvU/4x8Gy/z.8YLRJFv4sJo3Jk8MDXqIFkRPxoLy16a', '赵六', '2024004', '13800000004', 'zhaoliu@stu.reservation.edu.cn',  0, 1, NOW() - INTERVAL 15 DAY, NOW() - INTERVAL 15 DAY),
(6, 'teacher',  '$2a$10$sNz2VZki2sSTiHzosLqK8u1j2ym/rBokOGCWTdRnhcCz0ER2tkDpO', '教师演示', NULL,   '13800000010', 'teacher@reservation.edu.cn',      2, 1, NOW() - INTERVAL 26 DAY, NOW() - INTERVAL 26 DAY);

-- 教室 12 间：覆盖 普通教室(1)/实验室(2)/机房(3) 三类、3 个楼栋、不同容量与设备
INSERT INTO classroom (id, name, building, room_no, type, capacity, equipment, description, open_requirement, status, create_time, update_time) VALUES
(1,  'A101多媒体教室', '信息楼', 'A101', 1, 60,  '投影仪、音响、黑板',            '普通多媒体教室，适合小班授课',        '保持安静，禁止饮食',                     1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(2,  'A102多媒体教室', '信息楼', 'A102', 1, 80,  '投影仪、音响、黑板',            '可容纳 80 人的多媒体教室',            '保持安静，禁止饮食',                     1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(3,  'A201物理实验室', '信息楼', 'A201', 2, 40,  '物理实验台、示波器、稳压电源',  '基础物理实验',                        '须穿实验服，须有指导教师在场',           1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(4,  'A301计算机机房', '信息楼', 'A301', 3, 50,  '台式机 50 台、投影仪、空调',    '基础上机与程序设计课程',              '禁止自带 U 盘，须使用机房统一账号',      1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(5,  'B101化学实验室', '实验楼', 'B101', 2, 30,  '通风橱、实验台、药品柜',        '基础化学实验',                        '须穿实验服与护目镜，禁止单人操作',       1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(6,  'B102生物实验室', '实验楼', 'B102', 2, 45,  '显微镜、培养箱、超净工作台',    '生物实验',                            '须穿实验服，使用后须清理台面',           1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(7,  'B201计算机机房', '实验楼', 'B201', 3, 60,  '台式机 60 台、空调、投影仪',    '编程上机与实训',                      '禁止自带 U 盘，须使用机房统一账号',      1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(8,  'B301阶梯教室',   '实验楼', 'B301', 1, 100, '投影、扩音系统、中央空调',      '大班授课与考试',                      '优先保障教学与考试使用',                 1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(9,  'C101报告厅',     '综合楼', 'C101', 1, 120, 'LED 屏、音响、无线麦克风',     '讲座、报告、答辩',                    '须提前一天报备活动内容',                 1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(10, 'C201电子实验室', '综合楼', 'C201', 2, 40,  '焊接台、示波器、信号发生器',   '电子工艺实训',                        '须穿实验服，焊接须在通风区进行',         1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(11, 'C301计算机机房', '综合楼', 'C301', 3, 80,  '台式机 80 台、高性能服务器',   '高性能机房，支持大型软件实训',        '禁止自带 U 盘，须使用机房统一账号',      1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY),
(12, 'C401自习教室',   '综合楼', 'C401', 1, 90,  '课桌椅、空调、照明',            '开放自习教室',                        '保持安静，禁止占座',                     1, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY);

-- 预约数据 13 条：覆盖 待审核(0)/已通过(1)/已驳回(2)/已取消(3) 四状态；
-- 含当日、本周、本月数据；含一条"已通过且今日即将开始"（15:00 电路实验，支撑登录提醒演示）
INSERT INTO reservation (id, user_id, classroom_id, reserve_date, start_time, end_time, people_count, purpose, status, audit_remark, auditor_id, audit_time, create_time, update_time) VALUES
(1,  2, 1,  CURDATE(),                    '14:00:00', '16:00:00', 4,  '课程设计小组讨论',     0, NULL, NULL, NULL, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 2 HOUR),
(2,  3, 3,  CURDATE(),                    '15:00:00', '17:00:00', 6,  '电路基础实验',         1, '同意，注意实验安全', 1, NOW() - INTERVAL 1 HOUR, NOW() - INTERVAL 4 HOUR,  NOW() - INTERVAL 1 HOUR),
(3,  2, 5,  CURDATE() + INTERVAL 1 DAY,   '09:00:00', '11:00:00', 20, '化学实验课预习',       1, '同意', 1, NOW() - INTERVAL 3 HOUR, NOW() - INTERVAL 1 DAY,   NOW() - INTERVAL 3 HOUR),
(4,  4, 2,  CURDATE(),                    '10:00:00', '12:00:00', 1,  '个人自习',             2, '用途不明确，请填写具体教学/实验用途', 1, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 2 HOUR),
(5,  5, 7,  CURDATE() - INTERVAL 1 DAY,   '09:00:00', '10:00:00', 12, '社团活动场地申请',     3, NULL, NULL, NULL, NOW() - INTERVAL 2 DAY,   NOW() - INTERVAL 2 DAY),
(6,  2, 6,  CURDATE() - INTERVAL 2 DAY,   '08:00:00', '10:00:00', 15, '生物实验',             1, '同意', 1, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 3 DAY,   NOW() - INTERVAL 2 DAY),
(7,  3, 9,  CURDATE() + INTERVAL 1 DAY,   '14:00:00', '16:00:00', 30, '高数辅导答疑',         0, NULL, NULL, NULL, NOW() - INTERVAL 5 HOUR, NOW() - INTERVAL 5 HOUR),
(8,  4, 4,  CURDATE() - INTERVAL 3 DAY,   '14:00:00', '16:00:00', 8,  '程序设计上机练习',     1, '同意', 1, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 4 DAY,   NOW() - INTERVAL 3 DAY),
(9,  5, 8,  CURDATE() - INTERVAL 6 DAY,   '10:00:00', '12:00:00', 25, '英语角活动',           1, '同意', 1, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 7 DAY,   NOW() - INTERVAL 6 DAY),
(10, 2, 10, CURDATE() - INTERVAL 4 DAY,   '15:00:00', '17:00:00', 10, '产品推销宣讲会',       2, '非教学/实验用途，不符合教室使用规范', 1, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 4 DAY),
(11, 3, 11, CURDATE() - INTERVAL 5 DAY,   '09:00:00', '11:00:00', 20, '编程练习',             3, NULL, NULL, NULL, NOW() - INTERVAL 6 DAY,   NOW() - INTERVAL 6 DAY),
(12, 4, 12, CURDATE(),                    '08:00:00', '10:00:00', 2,  '考研自习',             1, '同意', 1, NOW() - INTERVAL 3 HOUR, NOW() - INTERVAL 1 DAY,   NOW() - INTERVAL 3 HOUR),
(13, 5, 1,  CURDATE(),                    '16:00:00', '18:00:00', 3,  '小组实验准备',         0, NULL, NULL, NULL, NOW() - INTERVAL 1 HOUR, NOW() - INTERVAL 1 HOUR);

-- 开放时段：12 间教室全部覆盖，工作日 08:00-12:00 / 14:00-18:00 两段
-- 生效区间为导入当天起 90 天，保证任意日期导入均可持续演示
INSERT INTO lab_open_slot (classroom_id, start_date, end_date, week_days, open_time, close_time, slot_capacity, status, remark, create_time, update_time)
SELECT c.id, CURDATE(), CURDATE() + INTERVAL 90 DAY, '1,2,3,4,5', '08:00:00', '12:00:00', LEAST(c.capacity, 60), 1, '工作日上午时段', NOW(), NOW()
FROM classroom c;

INSERT INTO lab_open_slot (classroom_id, start_date, end_date, week_days, open_time, close_time, slot_capacity, status, remark, create_time, update_time)
SELECT c.id, CURDATE(), CURDATE() + INTERVAL 90 DAY, '1,2,3,4,5', '14:00:00', '18:00:00', LEAST(c.capacity, 60), 1, '工作日下午时段', NOW(), NOW()
FROM classroom c;

-- 使用记录：对应 3 条已通过(status=1)的历史预约，覆盖 正常使用/部分使用/失约 三种状态
-- 规则：use_status=3（失约）时 actual_people 必须为 0；use_status=2 时 actual_people 必须小于 people_count
INSERT INTO usage_record (reservation_id, classroom_id, user_id, use_date, actual_people, use_status, check_remark, registrar_id, check_time, create_time, update_time) VALUES
(8, 4, 4, CURDATE() - INTERVAL 3 DAY, 8,  1, '按时到场，人数与预约一致',            1, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
(9, 8, 5, CURDATE() - INTERVAL 6 DAY, 12, 2, '实际到场 12 人，少于预约的 25 人',    1, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY),
(6, 6, 2, CURDATE() - INTERVAL 2 DAY, 0,  3, '预约后未到场，记为失约',              1, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY);

-- 用户收藏（用于个人中心常用教室快捷入口演示，每用户不超过 10 间）
INSERT INTO user_favorite (id, user_id, classroom_id, create_time) VALUES
(1, 2, 1,  NOW() - INTERVAL 10 DAY),
(2, 2, 3,  NOW() - INTERVAL 9 DAY),
(3, 3, 5,  NOW() - INTERVAL 8 DAY),
(4, 3, 7,  NOW() - INTERVAL 7 DAY),
(5, 3, 9,  NOW() - INTERVAL 6 DAY),
(6, 4, 12, NOW() - INTERVAL 5 DAY);

-- AI 配置（合规关键词库 / Prompt 模板，避免硬编码，可动态调整）
INSERT INTO ai_config (id, config_key, config_value, description, create_time, update_time) VALUES
(1, 'ai_enable',            'false',                                                                                        'AI 总开关（与 application.yml 中 ai.enable 保持一致）', NOW(), NOW()),
(2, 'ai_model',             'agnes-3.0-flash',                                                                              'AI 默认模型', NOW(), NOW()),
(3, 'compliance_keywords',  '商业推销,广告宣传,产品宣讲,娱乐聚会,非法集会,赌博活动,传销,违法讲座',                           '预约用途合规校验本地违规关键词库', NOW(), NOW()),
(4, 'prompt_parse',         '你是教室预约解析器，只输出JSON：{"date":"YYYY-MM-DD","startTime":"HH:mm","endTime":"HH:mm","capacity":int,"roomType":"普通教室|实验室|机房|null","purpose":"string"}', '自然语言预约解析 Prompt 模板', NOW(), NOW()),
(5, 'prompt_compliance',    '判断预约用途是否合规（是否与教学/实验/自习/竞赛等正当用途相关），输出{"compliant":true|false,"reason":"string"}', '预约合规校验 Prompt 模板', NOW(), NOW());
