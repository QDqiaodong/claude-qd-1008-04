import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来。
// 409 表示并发版本冲突：标记 isConflict，供页面弹「数据已变化」并强制刷新。
// 开赛校验的 400（被球洞阻止）/ 409（版本过期）响应体 data 带按最新状态重算的校验视图，
// 挂到 e.data 上，页面无需再次请求即可渲染受影响洞号 / 当前状态 / 修复提示。
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const message = error?.response?.data?.message || error?.message || '请求失败'
    const e = new Error(message)
    e.isConflict = status === 409
    e.data = error?.response?.data?.data || null
    return Promise.reject(e)
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 球场 ----------------
  listCourses: () => call(http.get('/courses')),
  getCourse: (id) => call(http.get(`/courses/${id}`)),
  createCourse: (d) => call(http.post('/courses', d)),
  updateCourse: (id, d) => call(http.put(`/courses/${id}`, d)),

  // ---------------- 球洞 ----------------
  listHoles: (p) => call(http.get('/holes', params(p || {}))),
  getHole: (id) => call(http.get(`/holes/${id}`)),
  createHole: (d) => call(http.post('/holes', d)),
  updateHole: (id, d) => call(http.put(`/holes/${id}`, d)),
  setHoleStatus: (id, target, expectedVersion) =>
    call(http.post(`/holes/${id}/status`, null,
      params({ target, ...(expectedVersion != null ? { expectedVersion } : {}) }))),

  // ---------------- 球员 ----------------
  listPlayers: () => call(http.get('/players')),
  getPlayer: (id) => call(http.get(`/players/${id}`)),
  createPlayer: (d) => call(http.post('/players', d)),
  updatePlayer: (id, d) => call(http.put(`/players/${id}`, d)),

  // ---------------- 开球时间 ----------------
  listTeeTimes: (p) => call(http.get('/tee-times', params(p || {}))),
  getTeeTime: (id) => call(http.get(`/tee-times/${id}`)),
  bookTeeTime: (d) => call(http.post('/tee-times', d)),
  updateTeeTime: (id, d) => call(http.put(`/tee-times/${id}`, d)),
  releaseTeeTime: (id) => call(http.post(`/tee-times/${id}/release`)),

  // ---------------- 赛事 ----------------
  listTournaments: () => call(http.get('/tournaments')),
  getTournament: (id) => call(http.get(`/tournaments/${id}`)),
  createTournament: (d) => call(http.post('/tournaments', d)),
  updateTournament: (id, d) => call(http.put(`/tournaments/${id}`, d)),
  advanceTournament: (id, target, expectedVersion) =>
    call(http.post(`/tournaments/${id}/advance`, null,
      params({ target, ...(expectedVersion != null ? { expectedVersion } : {}) }))),

  // ---------------- 赛事开赛前球场可用性校验 ----------------
  // GET：永远按最新球洞版本实时重算（可开赛 / 被阻止 / 已开始），只读不留痕；
  // POST start：按最新版本开赛，400 带阻断明细视图、409 带当前赛事与球洞状态。
  getTournamentStartCheck: (id) => call(http.get(`/tournaments/${id}/start-check`)),
  startTournament: (id, expectedVersion) =>
    call(http.post(`/tournaments/${id}/start`, null,
      params({ ...(expectedVersion != null ? { expectedVersion } : {}) }))),

  // ---------------- 赛事排行榜快照（临时结果 / 正式快照 / 收口失败原因） ----------------
  // 同一接口按赛事状态返回不同口径：报名中/进行中=临时结果；已结束=冻结正式快照；
  // 收口失败=失败原因+当前临时排名；修复前旧赛事=只读历史口径。
  getTournamentLeaderboard: (id) => call(http.get(`/tournaments/${id}/leaderboard`)),
  finalizeTournamentLeaderboard: (id, expectedVersion) =>
    call(http.post(`/tournaments/${id}/leaderboard/finalize`, null,
      params({ ...(expectedVersion != null ? { expectedVersion } : {}) }))),

  // ---------------- 排行榜（乐观锁演示） ----------------
  listLeaderboards: (p) => call(http.get('/leaderboards', params(p || {}))),
  getLeaderboard: (id) => call(http.get(`/leaderboards/${id}`)),
  createLeaderboard: (d) => call(http.post('/leaderboards', d)),
  updateLeaderboard: (id, d) => call(http.put(`/leaderboards/${id}`, d)),
  lockAndReadLeaderboard: (id) => call(http.post(`/leaderboards/${id}/lock-and-read`)),

  // ---------------- 成绩 ----------------
  listScores: (p) => call(http.get('/scores', params(p || {}))),
  recordScore: (d) => call(http.post('/scores', d)),

  // ---------------- 赛事参赛名单 ----------------
  listEntries: (tournamentId) => call(http.get(`/tournaments/${tournamentId}/entries`)),
  addEntry: (tournamentId, d) => call(http.post(`/tournaments/${tournamentId}/entries`, d)),

  // ---------------- 记分卡签核闭环 ----------------
  listScorecards: (tournamentId) => call(http.get('/scorecards', params({ tournamentId }))),
  draftScorecard: (tournamentId, playerId, roundNo) =>
    call(http.post('/scorecards/draft', null, params({ tournamentId, playerId, roundNo }))),
  getScorecard: (id) => call(http.get(`/scorecards/${id}`)),
  saveCardHole: (id, d) => call(http.post(`/scorecards/${id}/holes`, d)),
  submitScorecard: (id, d) => call(http.post(`/scorecards/${id}/submit`, d)),
  confirmScorecard: (id, d) => call(http.post(`/scorecards/${id}/confirm`, d)),
  disputeScorecard: (id, d) => call(http.post(`/scorecards/${id}/dispute`, d)),
  myScorecards: (playerId) => call(http.get('/scorecards/mine', params({ playerId }))),
  disputedScorecards: (tournamentId) =>
    call(http.get('/scorecards/disputed', params(tournamentId ? { tournamentId } : {}))),
  ruleScorecard: (id, d) => call(http.post(`/scorecards/${id}/rule`, d)),
  scorecardEvents: (id) => call(http.get(`/scorecards/${id}/events`)),
  officialResults: (tournamentId) => call(http.get('/scorecards/official', params({ tournamentId }))),
  signoffCheck: (tournamentId) => call(http.get('/scorecards/signoff-check', params({ tournamentId }))),

  // ---------------- 差点指数重算 ----------------
  listHandicapRules: () => call(http.get('/handicap-rules')),
  createHandicapRule: (d) => call(http.post('/handicap-rules', d)),
  deleteHandicapRule: (id) => call(http.delete(`/handicap-rules/${id}`)),
  handicapSummary: () => call(http.get('/handicaps/summary')),
  playerHandicap: (playerId) => call(http.get(`/handicaps/players/${playerId}`)),
  playerHandicapHistory: (playerId) => call(http.get(`/handicaps/players/${playerId}/history`)),
  missingDifficulty: () => call(http.get('/handicaps/missing-params')),
  recalcHandicaps: (d) => call(http.post('/handicaps/recalc', d)),
  listHandicapRuns: () => call(http.get('/handicap-runs')),
  getHandicapRun: (id) => call(http.get(`/handicap-runs/${id}`)),
  handicapRunErrors: (id) => call(http.get(`/handicap-runs/${id}/errors`)),
  runHandicapRecalc: (d) => call(http.post('/handicap-runs', d))
}
