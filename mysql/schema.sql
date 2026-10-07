SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 高尔夫球场 · 球洞 / 开球时间 / 球员 / 赛事 / 成绩
-- 库名 golf_course；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS golf_course
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE golf_course;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS handicap_history_round;
DROP TABLE IF EXISTS handicap_history;
DROP TABLE IF EXISTS handicap_run_error;
DROP TABLE IF EXISTS handicap_run;
DROP TABLE IF EXISTS handicap_rule;
DROP TABLE IF EXISTS tournament_start_attempt;
DROP TABLE IF EXISTS tournament_round_hole;
DROP TABLE IF EXISTS scorecard_event;
DROP TABLE IF EXISTS scorecard_hole;
DROP TABLE IF EXISTS scorecard;
DROP TABLE IF EXISTS tournament_entry;
DROP TABLE IF EXISTS score;
DROP TABLE IF EXISTS leaderboard_snapshot;
DROP TABLE IF EXISTS leaderboard;
DROP TABLE IF EXISTS tee_time;
DROP TABLE IF EXISTS tournament;
DROP TABLE IF EXISTS player;
DROP TABLE IF EXISTS hole;
DROP TABLE IF EXISTS course;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 球场（赛事开赛绑定的场地） ----------------
-- hole_count 为应配置洞数：开赛校验按 1..hole_count 逐洞读取该球场球洞，
-- 只有状态开放且标准杆（3/4/5）、码距（1~800）合法的洞才允许进入开赛。
-- version 为 @Version 乐观锁号（球场基础信息并发维护）。
CREATE TABLE course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_no VARCHAR(24) NOT NULL UNIQUE,
    name VARCHAR(48) NOT NULL,
    hole_count INT NOT NULL,
    note VARCHAR(64),
    created_at DATETIME,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_course_hole_count CHECK (hole_count BETWEEN 1 AND 18)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 球洞 ----------------
-- 归属球场（course_id），同球场内洞号唯一（uk_course_hole_no）；
-- 状态：开放 / 维护 / 封闭；par 取 3 / 4 / 5；yardage 1 ~ 800。
-- version 为 @Version 乐观锁号：多人同时维护同一洞时只有按最新版本的一次提交成功。
CREATE TABLE hole (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    hole_no INT NOT NULL,
    par INT NOT NULL,
    yardage INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_course_hole_no (course_id, hole_no),
    KEY idx_hole_course (course_id, hole_no),
    CONSTRAINT ck_hole_no CHECK (hole_no BETWEEN 1 AND 18),
    CONSTRAINT ck_hole_par CHECK (par IN (3, 4, 5)),
    CONSTRAINT ck_hole_yardage CHECK (yardage BETWEEN 1 AND 800),
    CONSTRAINT ck_hole_status CHECK (status IN ('开放', '维护', '封闭')),
    CONSTRAINT fk_hole_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 球员 ----------------
-- handicap 差点取值范围 0 ~ 54（管理员手工维护的基础差点）
-- handicap_index 为「差点指数重算」写回的当前计算指数（样本不足时为 NULL），
-- 计算依据、采用轮次与规则快照都在 handicap_history / handicap_history_round，
-- 绝不允许只改这一个数字而不留计算痕迹。
CREATE TABLE player (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_no VARCHAR(16) NOT NULL UNIQUE,
    name VARCHAR(48) NOT NULL,
    handicap INT NOT NULL,
    handicap_index DECIMAL(4,1),
    handicap_index_at DATETIME,
    phone VARCHAR(24),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 开球时间 ----------------
-- 同一球员同一天同一时段唯一（防止重复预订）；状态：可订 / 已订
CREATE TABLE tee_time (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_date DATE NOT NULL,
    player_id BIGINT NOT NULL,
    slot VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME,
    UNIQUE KEY uk_tee_player_slot (player_id, play_date, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事 ----------------
-- 状态：报名中 → 进行中 → 已结束
-- course_rating / slope_rating 为球场难度参数（差点差值计算的输入），需成对维护；
-- 旧赛事允许为 NULL —— 涉及这些赛事的轮次在差点计算中会被明确标记为「缺球场难度参数」并排除。
CREATE TABLE tournament (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_no VARCHAR(24) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    start_date DATE,
    end_date DATE,
    status VARCHAR(16) NOT NULL,
    location VARCHAR(48),
    course_id BIGINT NULL,
    course_rating DECIMAL(4,1),
    slope_rating INT,
    note VARCHAR(64),
    created_at DATETIME,
    -- 赛事乐观锁版本号：多人并发推进开赛 / 收口时，只有基于最新版本的一次成功。
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_tour_end_date (end_date),
    KEY idx_tour_course (course_id),
    CONSTRAINT ck_tour_status CHECK (status IN ('报名中', '进行中', '已结束')),
    CONSTRAINT fk_tour_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事本轮球洞冻结清单（开赛成功瞬间写入） ----------------
-- 开赛校验通过的同一事务内，把当时「开放且 par / 码距合法」的应打球洞逐洞冻结到本表：
-- 已进入进行中的赛事，本轮应打洞号 / par / 码距一律以本表为准，
-- 之后把 hole 改为维护 / 封闭不会静默改变本轮状态；但后续新赛事开赛必须重新校验。
-- 一场赛事 × 一个洞号唯一（uk_round_hole_tour_hole 兜底，并发开赛不可能写两份）。
CREATE TABLE tournament_round_hole (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    hole_id BIGINT NOT NULL,
    hole_no INT NOT NULL,
    par INT NOT NULL,
    yardage INT NOT NULL,
    hole_version BIGINT NOT NULL,
    frozen_at DATETIME NOT NULL,
    UNIQUE KEY uk_round_hole_tour_hole (tournament_id, hole_no),
    KEY idx_round_hole_tour (tournament_id, hole_no),
    CONSTRAINT fk_round_hole_tour FOREIGN KEY (tournament_id) REFERENCES tournament (id),
    CONSTRAINT fk_round_hole_hole FOREIGN KEY (hole_id) REFERENCES hole (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 开赛尝试审计流水（每次「开始」请求追加一行，永不修改 / 删除） ----------------
-- result：已开赛 / 已阻止 / 并发冲突。
-- 校验失败（已阻止）只新增流水，赛事仍「报名中」：不冻结球洞、不清报名名单与成绩草稿、
-- 不留下半个进行中状态；重试时服务端重新读取最新球洞版本，旧失败结果绝不复用。
-- blocked_json 为逐洞阻止明细（洞号 / 当前状态 / 修复提示）。
CREATE TABLE tournament_start_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    result VARCHAR(16) NOT NULL,
    expected_tournament_version BIGINT,
    expected_hole_version VARCHAR(128),
    current_tournament_version BIGINT,
    current_hole_version VARCHAR(128),
    blocked_json TEXT,
    message VARCHAR(512),
    created_at DATETIME NOT NULL,
    KEY idx_start_attempt_tour (tournament_id, id),
    CONSTRAINT fk_start_attempt_tour FOREIGN KEY (tournament_id) REFERENCES tournament (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事排行榜（乐观锁演示实体） ----------------
-- version 为 @Version 乐观锁版本号
CREATE TABLE leaderboard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    leader_name VARCHAR(48),
    total_score INT,
    version BIGINT,
    updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事排行榜快照（临时结果 / 正式快照 / 收口失败原因） ----------------
-- 一场赛事至多一行（uk_snapshot_tournament 兜底，并发收口只允许一份正式快照）。
-- snapshot_type：
--   临时结果 = 报名中 / 进行中按当前已录入成绩实时计算的口径，只在接口 / 页面返回并明确标注，不落库；
--   正式快照 = 收口成功瞬间冻结的全部参赛成绩与名次，之后刷新页面 / 接口永远只返回这份 JSON，
--              成绩的补录 / 修改 / 删除不再改变名次；
--   生成失败 = 收口校验未过（缺成绩 / 成绩属于不存在或非参赛球员 / 洞号杆数不合法），
--              事务回滚、赛事保持「进行中」、不留下半份正式结果；失败原因逐条存 failure_reasons。
-- official_payload_json：正式快照冻结体（完整名次行 + 每轮逐洞杆数），历史数据兼容用，永不修改。
-- version 为乐观锁版本号；generated_at 为正式快照生成（或上次收口失败）时间。
CREATE TABLE leaderboard_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    snapshot_type VARCHAR(16) NOT NULL,
    status_snapshot VARCHAR(16),
    failure_reasons TEXT,
    official_payload_json MEDIUMTEXT,
    generated_at DATETIME,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME,
    UNIQUE KEY uk_snapshot_tournament (tournament_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 成绩（按洞录入） ----------------
CREATE TABLE score (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    tournament_id BIGINT NOT NULL,
    hole_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    strokes INT NOT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事参赛名单（签核闭环的参赛范围） ----------------
-- 一场赛事 × 一名球员 × 一个轮次唯一；round_count 为该赛事应打轮次数。
-- 赛事收口时，每个参赛名额都必须存在对应轮次的「已签核」记分卡，否则阻止结束。
CREATE TABLE tournament_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    round_count INT NOT NULL DEFAULT 1,
    created_at DATETIME,
    UNIQUE KEY uk_entry_tour_player (tournament_id, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事记分卡（签核闭环主单，@Version 乐观锁） ----------------
-- 一张卡 = 赛事 × 轮次 × 球员，唯一。
-- 状态机：草稿 → 待球员确认 → 争议中 → 待球员确认 → … → 已签核（终态）
--   草稿：记分员可反复维护逐洞草稿；提交后 snapshot 固化，普通编辑不得改写。
--   待球员确认：等待球员整卡确认或对具体球洞提争议；确认不可撤回。
--   争议中：仅裁判可处理（维持 / 修正），裁决后回到待球员确认。
--   已签核：正式收口，其快照才计入正式排行榜；历史版本永不重复计入。
-- version 为 JPA @Version 乐观锁号：多人并发提交/确认/裁决时只有基于最新版本的一次成功。
CREATE TABLE scorecard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    expected_holes INT NOT NULL,
    total_strokes INT,
    -- 本次已固化快照：提交 / 每次裁决时重写为当前逐洞成绩的完整 JSON，历史快照存 scorecard_event
    snapshot_json MEDIUMTEXT,
    submitted_at DATETIME NULL,
    confirmed_at DATETIME NULL,
    last_submitter VARCHAR(48),
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_scorecard_tour_player_round (tournament_id, player_id, round_no),
    -- 差点重算按「球员 + 已签核」取轮次、按「状态 + 赛事批次」分批扫描，两个索引避免全表扫
    KEY idx_card_player_status (player_id, status),
    KEY idx_card_status_tour (status, tournament_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 记分卡逐洞明细（草稿工作区 + 争议/裁决工作数据） ----------------
-- 一张卡内洞号唯一（DB 层兜底，防止重复洞号）；strokes 为当前工作杆数。
-- dispute_reason：球员对该洞提出的争议理由（非空即表示该洞处于争议）。
-- ruled_strokes / ruling_note：裁判对该洞的修正杆数与裁决说明。
-- snapshot 固化后这些行保留为工作数据，但正式成绩只读 scorecard.snapshot_json。
CREATE TABLE scorecard_hole (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_id BIGINT NOT NULL,
    hole_no INT NOT NULL,
    par INT NOT NULL,
    strokes INT,
    dispute_reason VARCHAR(255),
    ruled_strokes INT,
    ruling_note VARCHAR(255),
    updated_at DATETIME,
    UNIQUE KEY uk_card_hole (scorecard_id, hole_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 记分卡流转事件（可追溯审计，永不修改/删除） ----------------
-- 每次提交、确认、争议、裁决都追加一行：
--   before_json 操作前快照（草稿首次提交可为空），after_json 操作后快照。
-- action：提交 / 确认 / 争议 / 维持 / 修正
CREATE TABLE scorecard_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(48) NOT NULL,
    detail VARCHAR(512),
    before_status VARCHAR(16),
    after_status VARCHAR(16),
    before_version BIGINT,
    after_version BIGINT,
    before_json MEDIUMTEXT,
    after_json MEDIUMTEXT,
    created_at DATETIME,
    KEY idx_event_card (scorecard_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 球员差点指数重算
-- =====================================================================

-- ---------------- 差点计算规则（带生效日期，版本化） ----------------
-- 同一生效日期只允许一条规则；规则一旦被任何计算 / 批次引用即不可删除，
-- 只能新增一条更新生效日的规则 —— 历史差点因此始终按当时规则可追溯。
-- best_count_table：「样本轮数:采用最低差值个数」映射，如 3:1,4:1,...,20:8；
--   样本数落在两档之间时向下取最近档。
-- max_differential：单轮差值截断上限（录入错误的异常成绩按上限计入，不再拉高指数）；
-- max_drop：单次重算允许的最大下调幅度（防止单次暴跌失真）。
CREATE TABLE handicap_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(48) NOT NULL,
    effective_date DATE NOT NULL,
    rounds_window INT NOT NULL,
    min_rounds INT NOT NULL,
    best_count_table VARCHAR(255) NOT NULL,
    max_differential DECIMAL(4,1) NOT NULL,
    max_drop DECIMAL(4,1) NOT NULL,
    note VARCHAR(255),
    created_at DATETIME,
    UNIQUE KEY uk_rule_effective_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 全量重算批次 ----------------
-- 按赛事日期分批扫描已签核记分卡，逐球员重算；单个球员失败不拖垮整批，
-- 失败明细落 handicap_run_error。
CREATE TABLE handicap_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_no VARCHAR(24) NOT NULL UNIQUE,
    as_of_date DATE NOT NULL,
    rule_id BIGINT NOT NULL,
    from_date DATE,
    to_date DATE,
    batch_size INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_players INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    insufficient_count INT NOT NULL DEFAULT 0,
    fail_count INT NOT NULL DEFAULT 0,
    triggered_by VARCHAR(48),
    started_at DATETIME,
    finished_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 批次失败明细 ----------------
CREATE TABLE handicap_run_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    batch_label VARCHAR(64),
    player_id BIGINT,
    player_name VARCHAR(48),
    message VARCHAR(255),
    created_at DATETIME,
    KEY idx_runerr_run (run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 差点指数计算历史（每次计算的完整依据） ----------------
-- 每次重算（批量或定向）每个球员一行：用的哪条规则（rule_json 快照）、
-- 基准日、样本量、采用个数、算出的指数、相对上次的升降、是否触发下调限制。
-- 状态：已计算 / 样本不足（可计轮次 < 规则最少轮次，index_value 为 NULL）。
CREATE TABLE handicap_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    run_id BIGINT,
    rule_id BIGINT NOT NULL,
    rule_json VARCHAR(512) NOT NULL,
    as_of_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    sample_size INT NOT NULL,
    used_count INT NOT NULL DEFAULT 0,
    index_value DECIMAL(4,1),
    previous_index DECIMAL(4,1),
    change_amount DECIMAL(4,1),
    drop_limited TINYINT(1) NOT NULL DEFAULT 0,
    missing_params TINYINT(1) NOT NULL DEFAULT 0,
    remark VARCHAR(255),
    created_at DATETIME,
    KEY idx_hcp_hist_player (player_id, id),
    KEY idx_hcp_hist_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 单次计算的轮次明细（采用 / 排除及原因） ----------------
-- 进入「最近 N 轮」窗口的每个已完成轮次一行：差值、是否被采用、
-- 未采用的排除原因（缺球场难度参数 / 差值未进入最低 K 个 / 快照无法解析）、
-- 以及差值是否被上限截断。
CREATE TABLE handicap_history_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    history_id BIGINT NOT NULL,
    tournament_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    play_date DATE,
    gross_score INT,
    course_rating DECIMAL(4,1),
    slope_rating INT,
    differential DECIMAL(5,1),
    capped TINYINT(1) NOT NULL DEFAULT 0,
    is_used TINYINT(1) NOT NULL DEFAULT 0,
    exclusion_reason VARCHAR(255),
    KEY idx_hhr_history (history_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

-- =====================================================================
-- 种子数据：球场
--   1 A场主球场 9 洞（洞 1~9，其中 5 维护、9 封闭 → 演示开赛被阻止）
--   2 B场老球场 9 洞（洞 10~18，全部开放 → 旧赛事历史口径）
--   3 练习场     9 洞（洞 19~27，其中第 5 洞维护、第 9 洞未配置 → 双重阻止演示）
--   4 锦标场     9 洞（洞 28~36，全部开放且数据齐全 → 演示可开赛）
-- =====================================================================
INSERT INTO course (id, course_no, name, hole_count, note, created_at, version) VALUES
(1, 'C-A',  'A场主球场', 9, '9 洞场，金秋会员邀请赛绑定', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(2, 'C-B',  'B场老球场', 9, '9 洞老场，旧赛事场地',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(3, 'C-DR', '练习场',    9, '青少年训练营绑定，待维护',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(4, 'C-T',  '锦标场',    9, '18 洞锦标场的前 9 洞，全部就绪', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0);

INSERT INTO hole (id, course_id, hole_no, par, yardage, status, created_at, version) VALUES
-- A场主球场：1~4、6~8 开放，5 维护，9 封闭（开赛校验会阻止 T-2026-001）
(1,  1, 1, 4, 412, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(2,  1, 2, 5, 528, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(3,  1, 3, 3, 168, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(4,  1, 4, 4, 395, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(5,  1, 5, 4, 430, '维护', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(6,  1, 6, 3, 190, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(7,  1, 7, 5, 545, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(8,  1, 8, 4, 408, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
(9,  1, 9, 3, 205, '封闭', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY, 0),
-- B场老球场：9 洞全部开放（旧赛事 5 历史口径用）
(10, 2, 1, 4, 388, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(11, 2, 2, 5, 502, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(12, 2, 3, 3, 175, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(13, 2, 4, 4, 401, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(14, 2, 5, 4, 420, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(15, 2, 6, 3, 185, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(16, 2, 7, 5, 531, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(17, 2, 8, 4, 398, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
(18, 2, 9, 4, 415, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, 0),
-- 练习场：第 5 洞维护中，第 9 洞故意不建档（未配置）；其余开放
(19, 3, 1, 4, 320, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(20, 3, 2, 4, 335, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(21, 3, 3, 3, 150, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(22, 3, 4, 4, 360, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(23, 3, 5, 4, 372, '维护', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(24, 3, 6, 3, 162, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(25, 3, 7, 4, 388, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
(26, 3, 8, 3, 178, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, 0),
-- 锦标场：9 洞全部开放且 par / 码距齐全（T-2026-007 可直接开赛）
(28, 4, 1, 4, 420, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(29, 4, 2, 4, 385, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(30, 4, 3, 3, 172, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(31, 4, 4, 5, 548, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(32, 4, 5, 4, 436, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(33, 4, 6, 3, 198, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(34, 4, 7, 5, 560, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(35, 4, 8, 4, 412, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0),
(36, 4, 9, 4, 445, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, 0);

INSERT INTO player (id, player_no, name, handicap, phone, note, created_at) VALUES
(1,  'P-001', '林浩',     12, '13801012001', '单差点进阶',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(2,  'P-002', '陈雨桐',   18, '13801012002', '周末常客',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(3,  'P-003', '王启明',    8, '13801012003', '低差点',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(4,  'P-004', '赵子涵',   24, '13801012004', '新手',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(5,  'P-005', '刘振华',    0, '13801012005', '职业教练',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(6,  'P-006', '孙佳怡',   15, '13801012006', '女子组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(7,  'P-007', '周文博',   30, '13801012007', '长者组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(8,  'P-008', '吴晓彤',   20, '13801012008', '业余赛事常客',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(9,  'P-009', '郑凯',     10, '13801012009', '稳定发挥',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(10, 'P-010', '黄诗韵',   28, '13801012010', '新人',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY);

INSERT INTO tee_time (id, play_date, player_id, slot, status, note, created_at) VALUES
(1,  '2026-10-01', 1,  '07:00', '已订', '晨场第一开球',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(2,  '2026-10-01', 3,  '07:10', '已订', NULL,             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(3,  '2026-10-01', 5,  '09:00', '已订', '教练陪打',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(4,  '2026-10-01', 2,  '09:10', '可订', '待确认',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(5,  '2026-10-02', 4,  '08:00', '已订', '新手首场',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(6,  '2026-10-02', 6,  '08:10', '已订', NULL,             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(7,  '2026-10-02', 7,  '13:30', '已订', '长者组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(8,  '2026-10-03', 8,  '10:00', '已订', '赛事热身',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(9,  '2026-10-03', 9,  '10:10', '可订', '待拼场',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(10, '2026-10-03', 10, '15:00', '已订', '新人练习',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY);

INSERT INTO tournament (id, tournament_no, name, start_date, end_date, status, location, course_id, course_rating, slope_rating, note, created_at) VALUES
(1, 'T-2026-001', '金秋会员邀请赛', '2026-10-10', '2026-10-12', '报名中', 'A场主球场', 1, 28.8, 116, '9 洞比杆赛：第 5 洞维护、第 9 洞封闭，开赛会被阻止', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, 'T-2026-002', '青少年高尔夫训练营', '2026-11-01', '2026-11-03', '报名中', '练习场', 3, NULL, NULL, '练习场第 5 洞维护、第 9 洞未配置', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY),
-- 历史已结束赛事（差点重算的轮次来源）；T-2026-005 为旧数据，未维护球场难度参数（演示缺参与定向重算）
(3, 'T-2026-003', '春季会员杯', '2026-06-12', '2026-06-14', '已结束', 'A场主球场', 1, 28.6, 118, NULL,               UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(4, 'T-2026-004', '夏季公开赛', '2026-07-10', '2026-07-12', '已结束', 'A场主球场', 1, 29.1, 122, NULL,               UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(5, 'T-2026-005', '迎新友谊赛', '2026-08-07', '2026-08-09', '已结束', 'B场老球场', 2, NULL, NULL, '旧数据：未维护球场难度参数', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(6, 'T-2026-006', '秋季排位赛', '2026-09-04', '2026-09-06', '已结束', 'A场主球场', 1, 28.3, 115, NULL,               UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
-- 可直接开赛的赛事：锦标场 9 洞全部开放且数据齐全
(7, 'T-2026-007', '十月开场杯', '2026-10-18', '2026-10-19', '报名中', '锦标场', 4, 30.2, 128, '9 洞就绪，可体验直接开赛', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY);

INSERT INTO leaderboard (id, tournament_id, round_no, leader_name, total_score, version, updated_at) VALUES
(1, 1, 1, '王启明', 33, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2, 1, 2, '林浩',   35, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(3, 2, 1, '孙佳怡', 40, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT INTO score (id, player_id, tournament_id, hole_id, round_no, strokes, created_at) VALUES
(1,  1, 1, 1, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2,  1, 1, 2, 1, 6,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3,  1, 1, 3, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4,  3, 1, 1, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(5,  3, 1, 2, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(6,  3, 1, 3, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(7,  5, 1, 1, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(8,  5, 1, 2, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(9,  6, 2, 19, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(10, 6, 2, 20, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(11, 6, 2, 21, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(12, 8, 2, 25, 1, 6,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- =====================================================================
-- 种子数据：赛事参赛名单（赛事 1 为 7 个开放洞的一轮比杆赛）
-- =====================================================================
INSERT INTO tournament_entry (id, tournament_id, player_id, round_count, created_at) VALUES
(1, 1, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 1, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(3, 1, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(4, 1, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(5, 2, 6, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(6, 2, 8, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
-- 历史赛事参赛名单（与下方已签核记分卡一一对应）
(7,  3, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(8,  3, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(9,  3, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(10, 3, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(11, 3, 6, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(12, 3, 8, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 118 DAY),
(13, 4, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(14, 4, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(15, 4, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(16, 4, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(17, 4, 6, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(18, 4, 8, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
(19, 5, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 62 DAY),
(20, 5, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 62 DAY),
(21, 5, 4, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 62 DAY),
(22, 6, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY),
(23, 6, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY),
(24, 6, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY);

-- =====================================================================
-- 种子数据：记分卡签核闭环演示
--   卡1 刘振华：草稿且 7 洞齐全（可直接体验提交）
--   卡2 林浩：草稿缺洞（提交时应被拦截并列出缺失/重复原因）
--   卡3 王启明：已提交待球员确认（快照已固化）
--   卡4 陈雨桐：争议中（第 2 洞，等待裁判裁决；赛事收口时应阻止结束）
-- =====================================================================
INSERT INTO scorecard
(id, tournament_id, player_id, round_no, status, version, expected_holes, total_strokes, snapshot_json,
 submitted_at, confirmed_at, last_submitter, created_at, updated_at) VALUES
(1, 1, 5, 1, '草稿', 0, 7, NULL, NULL,
 NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2, 1, 1, 1, '草稿', 0, 7, NULL, NULL,
 NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3, 1, 3, 1, '待球员确认', 1, 7, 28,
 '{"roundNo":1,"totalStrokes":28,"holes":[{"holeNo":1,"par":4,"strokes":3},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":4}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 1, 2, 1, '争议中', 2, 7, 33,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- =====================================================================
-- 种子数据：历史已签核记分卡（差点指数重算的「已完成轮次」来源）
--   春季会员杯(3) / 夏季公开赛(4) / 秋季排位赛(6) 已维护球场难度参数；
--   迎新友谊赛(5) 未维护（旧数据缺参，计算时明确排除并可定向重算）。
--   覆盖样例：球员 1/2/3 有 3 轮可计 → 出指数；球员 5/6/8 仅 2 轮、
--   球员 4 仅缺参赛事的 1 轮 → 样本不足。
-- =====================================================================
INSERT INTO scorecard
(id, tournament_id, player_id, round_no, status, version, expected_holes, total_strokes, snapshot_json,
 submitted_at, confirmed_at, last_submitter, created_at, updated_at) VALUES
-- 春季会员杯（2026-06-14 结束，rating 28.6 / slope 118）
(5, 3, 1, 1, '已签核', 2, 7, 40,
 '{"roundNo":1,"totalStrokes":40,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":7},{"holeNo":3,"par":3,"strokes":4},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":7},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
(6, 3, 2, 1, '已签核', 2, 7, 45,
 '{"roundNo":1,"totalStrokes":45,"holes":[{"holeNo":1,"par":4,"strokes":7},{"holeNo":2,"par":5,"strokes":8},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
(7, 3, 3, 1, '已签核', 2, 7, 33,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
(8, 3, 5, 1, '已签核', 2, 7, 30,
 '{"roundNo":1,"totalStrokes":30,"holes":[{"holeNo":1,"par":4,"strokes":4},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
(9, 3, 6, 1, '已签核', 2, 7, 43,
 '{"roundNo":1,"totalStrokes":43,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":7},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
(10, 3, 8, 1, '已签核', 2, 7, 47,
 '{"roundNo":1,"totalStrokes":47,"holes":[{"holeNo":1,"par":4,"strokes":7},{"holeNo":2,"par":5,"strokes":8},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":7},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":9},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 114 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 113 DAY),
-- 夏季公开赛（2026-07-12 结束，rating 29.1 / slope 122）
(11, 4, 1, 1, '已签核', 2, 7, 41,
 '{"roundNo":1,"totalStrokes":41,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":7},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":7},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
(12, 4, 2, 1, '已签核', 2, 7, 46,
 '{"roundNo":1,"totalStrokes":46,"holes":[{"holeNo":1,"par":4,"strokes":7},{"holeNo":2,"par":5,"strokes":8},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":7},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
(13, 4, 3, 1, '已签核', 2, 7, 34,
 '{"roundNo":1,"totalStrokes":34,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":4},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
(14, 4, 5, 1, '已签核', 2, 7, 29,
 '{"roundNo":1,"totalStrokes":29,"holes":[{"holeNo":1,"par":4,"strokes":4},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
(15, 4, 6, 1, '已签核', 2, 7, 44,
 '{"roundNo":1,"totalStrokes":44,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":8},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
(16, 4, 8, 1, '已签核', 2, 7, 48,
 '{"roundNo":1,"totalStrokes":48,"holes":[{"holeNo":1,"par":4,"strokes":7},{"holeNo":2,"par":5,"strokes":9},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":7},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":9},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 86 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 87 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 85 DAY),
-- 迎新友谊赛（2026-08-09 结束，旧数据：未维护球场难度参数）
(17, 5, 1, 1, '已签核', 2, 7, 38,
 '{"roundNo":1,"totalStrokes":38,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":7},{"holeNo":3,"par":3,"strokes":4},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":7},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 59 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY),
(18, 5, 2, 1, '已签核', 2, 7, 43,
 '{"roundNo":1,"totalStrokes":43,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":7},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 59 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY),
(19, 5, 4, 1, '已签核', 2, 7, 52,
 '{"roundNo":1,"totalStrokes":52,"holes":[{"holeNo":1,"par":4,"strokes":8},{"holeNo":2,"par":5,"strokes":9},{"holeNo":3,"par":3,"strokes":6},{"holeNo":4,"par":4,"strokes":8},{"holeNo":6,"par":3,"strokes":6},{"holeNo":7,"par":5,"strokes":9},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 59 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 57 DAY),
-- 秋季排位赛（2026-09-06 结束，rating 28.3 / slope 115）
(20, 6, 1, 1, '已签核', 2, 7, 39,
 '{"roundNo":1,"totalStrokes":39,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":4},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":7},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY),
(21, 6, 2, 1, '已签核', 2, 7, 44,
 '{"roundNo":1,"totalStrokes":44,"holes":[{"holeNo":1,"par":4,"strokes":6},{"holeNo":2,"par":5,"strokes":8},{"holeNo":3,"par":3,"strokes":5},{"holeNo":4,"par":4,"strokes":6},{"holeNo":6,"par":3,"strokes":5},{"holeNo":7,"par":5,"strokes":8},{"holeNo":8,"par":4,"strokes":6}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY),
(22, 6, 3, 1, '已签核', 2, 7, 32,
 '{"roundNo":1,"totalStrokes":32,"holes":[{"holeNo":1,"par":4,"strokes":4},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 31 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY);

-- 卡1 刘振华草稿：7 洞齐全
INSERT INTO scorecard_hole (id, scorecard_id, hole_no, par, strokes, dispute_reason, ruled_strokes, ruling_note, updated_at) VALUES
(1,  1, 1, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2,  1, 2, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3,  1, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4,  1, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(5,  1, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(6,  1, 7, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(7,  1, 8, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
-- 卡2 林浩草稿：只录了 5 洞（缺第 7、8 洞），提交时应被拦截
(8,  2, 1, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(9,  2, 2, 5, 6, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(10, 2, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(11, 2, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(12, 2, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
-- 卡3 王启明：待确认快照对应逐洞
(13, 3, 1, 4, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(14, 3, 2, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(15, 3, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(16, 3, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(17, 3, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(18, 3, 7, 5, 6, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(19, 3, 8, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
-- 卡4 陈雨桐：争议中，第 2 洞球员主张记错（6 → 应为 5）
(20, 4, 1, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(21, 4, 2, 5, 6, '同组均记为5杆，记分卡6杆系笔误', NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(22, 4, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(23, 4, 4, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(24, 4, 6, 3, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(25, 4, 7, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(26, 4, 8, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- 卡3：提交事件（before 为空草稿，after 为固化快照）
INSERT INTO scorecard_event
(id, scorecard_id, action, actor, detail, before_status, after_status, before_version, after_version,
 before_json, after_json, created_at) VALUES
(1, 3, '提交', '记分员小周', '7 洞齐全，提交后进入待球员确认', '草稿', '待球员确认', 0, 1,
 NULL,
 '{"roundNo":1,"totalStrokes":28,"holes":[{"holeNo":1,"par":4,"strokes":3},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":4}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
-- 卡4：提交 → 球员就第 2 洞提争议
(2, 4, '提交', '记分员小周', '7 洞齐全，提交后进入待球员确认', '草稿', '待球员确认', 0, 1,
 NULL,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR),
(3, 4, '争议', '陈雨桐', '第 2 洞提出争议：同组均记为5杆，记分卡6杆系笔误', '待球员确认', '争议中', 1, 2,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6,"disputeReason":"同组均记为5杆，记分卡6杆系笔误"},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- =====================================================================
-- 种子数据：已结束赛事开赛时冻结的本轮球洞清单
--   历史赛事 3/4/6 绑定 A 场（course_id=1），冻结 1、2、3、4、6、7、8 共 7 洞
--   （开赛瞬间这些洞全部开放；第 5、9 洞当时即不在应打清单内，之后改维护 / 封闭也不影响这些旧轮）；
--   旧赛事 5 绑定 B 场（course_id=2），冻结其 1~9 洞。
-- =====================================================================
INSERT INTO tournament_round_hole (tournament_id, hole_id, hole_no, par, yardage, hole_version, frozen_at) VALUES
(3, 1, 1, 4, 412, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 2, 2, 5, 528, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 3, 3, 3, 168, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 4, 4, 4, 395, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 6, 6, 3, 190, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 7, 7, 5, 545, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, 8, 8, 4, 408, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(4, 1, 1, 4, 412, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 2, 2, 5, 528, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 3, 3, 3, 168, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 4, 4, 4, 395, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 6, 6, 3, 190, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 7, 7, 5, 545, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(4, 8, 8, 4, 408, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 92 DAY),
(6, 1, 1, 4, 412, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 2, 2, 5, 528, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 3, 3, 3, 168, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 4, 4, 4, 395, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 6, 6, 3, 190, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 7, 7, 5, 545, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(6, 8, 8, 4, 408, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
(5, 10, 1, 4, 388, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 11, 2, 5, 502, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 12, 3, 3, 175, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 13, 4, 4, 401, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 14, 5, 4, 420, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 15, 6, 3, 185, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 16, 7, 5, 531, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 17, 8, 4, 398, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY),
(5, 18, 9, 4, 415, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 64 DAY);

-- =====================================================================
-- 种子数据：默认差点计算规则（WHS 口径，2026-01-01 起生效）
--   最近 20 轮；样本 ≥ 3 才出指数；按样本数取最低差值个数（3轮取1 … 20轮取8）；
--   单轮差值超 40.0 按 40.0 截断；单次重算下调幅度不超过 5.0。
-- =====================================================================
INSERT INTO handicap_rule
(id, rule_name, effective_date, rounds_window, min_rounds, best_count_table, max_differential, max_drop, note, created_at) VALUES
(1, 'WHS标准口径v1', '2026-01-01', 20, 3,
 '3:1,4:1,5:1,6:2,7:2,8:2,9:3,10:3,11:3,12:4,13:4,14:4,15:5,16:5,17:5,18:6,19:6,20:8',
 40.0, 5.0, '默认规则：最近20轮、样本≥3、按样本数取最低差值、单轮截断40、单次下调≤5',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY);
