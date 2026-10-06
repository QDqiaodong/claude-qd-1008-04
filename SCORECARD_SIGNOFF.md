# 赛事记分卡签核闭环

在既有「球洞 / 开球时间 / 球员 / 赛事 / 成绩」之上新增的完整闭环：记分员、球员、裁判围绕
**同一赛事 × 轮次 × 球员的一轮逐洞成绩**完成 提交 → 确认 / 争议 → 裁决 → 再确认 → 正式收口。

## 状态机

```
草稿 ──记分员提交(固化快照)──▶ 待球员确认 ──球员整卡确认──▶ 已签核(终态，不可撤回)
                                  │ ▲                         │
                                  │ │ 裁判裁决后发回           │ 只有「已签核」卡的
                                  │ └────────────────          │ 当前快照计入正式排行榜
                                  ▼                           │
                               争议中 ──裁判维持/修正──────────┘
```

- **草稿**：记分员按赛事/轮次/球员维护逐洞杆数；只有赛事「进行中」、当前开放的应打球洞
  每洞都有 ≥1 的合法杆数、且无重复洞号时才能提交。
- **提交**：固化 `scorecard.snapshot_json` 快照并进入「待球员确认」；之后普通草稿编辑无法
  改写快照（接口在非草稿状态直接拒绝）。
- **球员**：只能列出/处理本人的卡（后端校验姓名归属），可整卡确认（终态不可撤回），
  也可对具体球洞填写争议理由；争议中成绩不计入正式排行榜。
- **裁判**：只处理「争议中」的卡，逐洞维持原杆数或给出修正杆数（必须写裁决说明），
  裁决后重新固化快照并发回球员重新确认。
- **收口**：赛事 进行中 → 已结束 前调用签核检查，任一参赛名额的任一轮次缺卡/草稿/待确认/
  争议中都会阻止收口并列出逐条原因（`TournamentService.advance` 强制走该检查）。

## 并发与留痕

- `scorecard.version` 为 JPA `@Version` 乐观锁；所有写接口都要带 `expectedVersion`。
  版本过期返回 **HTTP 409**，消息带当前状态/版本（`ConflictException` 与
  `ObjectOptimisticLockingFailureException` 双重兜底），前端弹「数据已变化」并强制刷新，
  绝不静默覆盖另一人的结果。
- 每次 提交 / 确认 / 争议 / 维持 / 修正 都向 `scorecard_event` 追加一行，含操作人、
  前后状态、前后版本号、前后完整快照 JSON。正式排行榜只解析已签核卡的**当前**快照，
  历史版本永不重复计入。

## 接口一览（/api）

| 角色 | 方法与路径 | 说明 |
| --- | --- | --- |
| 记分员 | `POST /scorecards/draft?tournamentId&playerId&roundNo` | 取/建草稿（须在参赛名单） |
| 记分员 | `POST /scorecards/{id}/holes` | 保存单洞草稿 `{holeNo, strokes, expectedVersion}` |
| 记分员 | `POST /scorecards/{id}/submit` | 提交并固化快照 `{submitter, expectedVersion}` |
| 球员 | `GET /scorecards/mine?playerId` | 只列本人的卡 |
| 球员 | `POST /scorecards/{id}/confirm` | 整卡确认 `{playerName, expectedVersion}` |
| 球员 | `POST /scorecards/{id}/dispute` | 按洞争议 `{playerName, expectedVersion, disputes:[{holeNo,reason}]}` |
| 裁判 | `GET /scorecards/disputed?tournamentId` | 争议队列 |
| 裁判 | `POST /scorecards/{id}/rule` | `{referee, expectedVersion, rulings:[{holeNo,ruledStrokes,note}]}` |
| 通用 | `GET /scorecards/{id}` / `GET /scorecards/{id}/events` | 详情 / 全量留痕 |
| 收口 | `GET /scorecards/official?tournamentId` | 正式排行榜（仅已签核） |
| 收口 | `GET /scorecards/signoff-check?tournamentId` | 收口前检查与阻止原因 |
| 名单 | `GET/POST /tournaments/{id}/entries` | 参赛名单（签核范围） |

前端入口：球场平面图新增「记分卡签核」热点（`#/scorecard`），内含记分员 / 球员 / 裁判 /
收口与正式成绩四个页签，详情页可展开每次提交与裁决前后的快照对比时间线。
