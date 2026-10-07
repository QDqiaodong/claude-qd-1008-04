# 赛事开赛前球场可用性校验

在既有「球场 / 球洞 / 赛事 / 参赛名单 / 记分卡 / 成绩」之上新增的开赛闸门：赛事从
**报名中 → 进行中** 时，必须先按该赛事绑定球场读取**全部应打球洞**，只有状态为「开放」且
标准杆、码距均合法的球洞才允许进入开赛。校验失败时赛事仍保持报名中，修复后可重新尝试。

## 四态（页面与接口同一口径 `StartCheckView.phase`）

| 状态 | 触发情形 | 页面 / 接口含义 |
| --- | --- | --- |
| `校验中` | 正在请求校验接口 | 前端 loading 占位，不落库 |
| `可开赛` | 赛事报名中，全部应打洞均开放且 par / 码距合法 | 可点「开始赛事」；`eligibleHoleNos` 列出应打洞号 |
| `被阻止` | 赛事报名中，存在阻断项 | `blockedHoles` 逐条给出**洞号 / 当前状态 / par / 码距 / 阻断原因 / 修复提示** |
| `已开始` | 进行中 / 已结束 | 返回开赛瞬间**冻结**的应打洞集，不随后续球洞维护变化 |

## 阻断规则（按赛事绑定球场，应打洞号 = 1 ~ `course.hole_count`）

- **未绑定球场**：球场级阻断（`holeNo=null`，状态「基础数据不完整」），提示先在编辑赛事中绑定。
- **球场应打洞数非法**：`hole_count` 不在 1 ~ 18。
- **未配置**：应打洞号在该球场上查无此洞（缺号）→ 提示补建该洞。
- **维护 / 封闭**：球洞存在但状态非「开放」→ 提示改回开放（封闭需重新开放并核对 par / 码距）。
- **基础数据不完整（逐洞）**：par 不属于 3 / 4 / 5、码距不在 1 ~ 800、或状态值无法识别。
- 应打洞数之外的**额外洞**不阻断本场，仅在 `notices` 中提示。

## 原子性、失败恢复与重试

- 开赛在独立事务内执行「赛事行悲观锁 → 重新读取最新球洞 → 校验 → 冻结洞集 → 翻进行中」，
  任一阻断都整体回滚：**赛事仍报名中，报名名单（`tournament_entry`）、成绩（`score`）、
  记分卡草稿（`scorecard*`）原样保留，绝不留下半个「进行中」状态**。
- 失败后用第二个独立事务把逐洞明细写入 / 覆盖 `tournament_start_check`（`被阻止`），
  只用于留痕与失败恢复；GET 校验接口永远实时重算，不读这份旧结果。
- 管理员修复球洞后重试：每次都**重新查询最新球洞版本**并整行覆盖旧留痕，绝不复用第一次失败结果。
- 成功后留痕被覆盖为 `可开赛`，冻结应打洞号存 `eligible_hole_nos`。

## 版本与并发

- `tournament.version`：赛事乐观锁 + 开赛时赛事行悲观写锁。多人同时点「开始」时只有
  一个事务能拿到赛事行锁完成开赛；`expectedVersion` 过期（预检后他人先开赛 / 改动）返回
  **HTTP 409**，响应体 `data` 为按当前赛事与球洞状态重算的视图，前端提示「数据已变化」。
- `hole.version`：球洞 JPA `@Version`。两人同时维护同一球洞（状态 / par / 码距）时，
  携带过期版本的写操作返回 409；`POST /holes/{id}/status?target&expectedVersion=` 与
  `PUT /holes/{id}`（body 带 `version`）都做版本闸门。
- 刷新赛事页 / 轮询永远拿最新结果，不会因为页面旧而放过阻断。

## 开赛后冻结：不静默改变本轮

- 开赛冻结的应打洞集是**本轮**排行榜（`LeaderboardSnapshotService`）、记分卡
  （`ScorecardService`）与成绩录入（`ScoreService`）的统一应打口径：
  开赛之后把某洞改为维护 / 封闭，本轮仍按冻结洞集进行，**不静默改变本轮状态**。
- 冻结记录只属于这一场赛事；**后续新赛事没有冻结记录，必须重新校验**。
- 修复前已开赛 / 已结束、没有冻结记录的历史赛事，按「绑定球场当前开放洞 → 全库开放洞」
  只读回退，并在视图 `notices` 中明确标注为兼容口径。

## 接口一览（/api）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /tournaments/{id}/start-check` | 只读实时校验（可开赛 / 被阻止 / 已开始），不落任何痕迹 |
| `POST /tournaments/{id}/start?expectedVersion=` | 开赛：成功 200 返回「已开始」视图；阻断 **400**（`data` 为被阻止视图）；过期 / 已开赛 **409**（`data` 为当前状态） |
| `POST /tournaments/{id}/advance?target=进行中&expectedVersion=` | 与 `/start` 等价的状态机入口（推进到「已结束」仍走排行榜收口） |
| `GET /courses` / `POST /courses` / `PUT /courses/{id}` | 球场列表 / 建档 / 维护（`holeCount` 1~18） |
| `POST /holes/{id}/status?target=&expectedVersion=` | 维护球洞状态（带版本号，过期 409） |
| `PUT /holes/{id}` | 维护 par / 码距 / 状态（body 可带 `version`） |
| `GET /holes?courseId=&status=&par=` | 按球场列出球洞 |

赛事基础维护 `PUT /tournaments/{id}`：**绑定 / 改绑球场只允许在报名中**；进行中 / 已结束
赛事改绑一律拒绝（本轮口径已冻结），请为后续新赛事选择球场。

## 持久化

- 新表 `course`（球场，`hole_count` 为应打洞数）。
- `hole` 增加 `course_id` 与 `version`，唯一约束由 `hole_no UNIQUE` 改为
  `uk_hole_course_no(course_id, hole_no)`。
- `tournament` 增加可空 `course_id`（旧赛事允许 NULL）。
- 新表 `tournament_start_check`（一场赛事至多一行，`uk_start_check_tournament` 兜底）：
  `check_type=可开赛/被阻止`、`eligible_hole_nos`（冻结洞集）、`blocked_holes_json`
  （逐洞阻断明细）、`course_version_hash`、`checked_at`、`version`。

前端入口：赛事中心「赛事」页签新增开赛校验面板（四态徽标、阻断明细表、重新校验 / 开始按钮），
报名中每 8 秒按最新球洞刷新结果；球洞页按球场筛选并展示版本、维护状态带版本校验；
新建 / 编辑赛事弹窗可选择绑定球场。
