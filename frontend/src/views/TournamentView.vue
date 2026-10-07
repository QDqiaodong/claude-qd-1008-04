<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import api from '../api'

const tournaments = ref([])
const players = ref([])
const holes = ref([])
const courses = ref([])

const leaderboard = ref(null)
const scores = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)

const selectedTourId = ref(null)
const activeTab = ref('tournament')

// ---- 赛事 ----
const tourDialog = ref(false)
const savingTour = ref(false)
const tourForm = ref(blankTour())
function blankTour() {
  return { tournamentNo: '', name: '', startDate: '2026-10-10', endDate: '2026-10-12', status: '报名中', location: '', courseId: null, courseRating: null, slopeRating: null, note: '' }
}

// ---- 赛事编辑（含球场绑定与难度参数补齐） ----
const editDialog = ref(false)
const savingEdit = ref(false)
const editForm = ref({})
function openEdit(t) {
  editForm.value = {
    id: t.id, name: t.name, startDate: t.startDate, endDate: t.endDate,
    location: t.location, courseId: t.course ? t.course.id : null,
    courseRating: t.courseRating, slopeRating: t.slopeRating, note: t.note,
    status: t.status
  }
  editDialog.value = true; error.value = ''; tip.value = ''
}
async function saveEdit() {
  savingEdit.value = true; error.value = ''
  try {
    const d = {
      ...editForm.value,
      course: editForm.value.courseId ? { id: Number(editForm.value.courseId) } : null,
      courseRating: editForm.value.courseRating === '' || editForm.value.courseRating == null ? null : Number(editForm.value.courseRating),
      slopeRating: editForm.value.slopeRating === '' || editForm.value.slopeRating == null ? null : Number(editForm.value.slopeRating)
    }
    await api.updateTournament(d.id, d)
    tip.value = `赛事「${d.name}」已更新${d.course ? '，球场绑定已保存，可重新做开赛校验' : ''}`
    editDialog.value = false
    await refreshAfterMutation()
  } catch (e) { error.value = e.message } finally { savingEdit.value = false }
}

// ---- 成绩 ----
const scoreDialog = ref(false)
const savingScore = ref(false)
const scoreForm = ref(blankScore())
function blankScore() {
  return { tournamentId: null, playerId: '', holeId: '', roundNo: 1, strokes: null }
}

const canStart = (t) => t && t.status === '报名中'
const canEnd = (t) => t && t.status === '进行中'
const selectedTour = computed(() => tournaments.value.find(t => t.id === selectedTourId.value) || null)

// ---------------- 开赛前球场可用性校验 ----------------
// state: 校验中（前端请求进行中）/ 可开赛 / 已阻止 / 已开始（赛事已进行中或已结束）
const startChecks = ref({}) // tournamentId -> StartCheckView
const checkLoading = ref(false)
const starting = ref(false)

const selectedCheck = computed(() => startChecks.value[selectedTourId.value] || null)
const checkState = computed(() => {
  if (checkLoading.value) return '校验中'
  const c = selectedCheck.value
  if (!c) return '校验中'
  if (c.state === '已开始') return '已开始'
  return c.state // 可开赛 / 已阻止
})
const checkTagType = computed(() => ({
  校验中: 'info', 可开赛: 'success', 已阻止: 'danger', 已开始: 'warning'
}[checkState.value] || 'info'))

async function loadTournaments() {
  tournaments.value = (await api.listTournaments()) || []
  if (!selectedTourId.value && tournaments.value.length) {
    selectedTourId.value = tournaments.value[0].id
  }
}
async function loadLeaderboard() {
  if (!selectedTourId.value) { leaderboard.value = null; return }
  // 不覆盖面板的失败提示：临时/失败视图是正常 200 返回，直接替换；请求异常时保留旧面板
  leaderboard.value = (await api.getTournamentLeaderboard(selectedTourId.value)) || null
}
async function loadScores() {
  if (!selectedTourId.value) { scores.value = []; return }
  scores.value = (await api.listScores({ tournamentId: selectedTourId.value })) || []
}
async function loadCourses() { courses.value = (await api.listCourses()) || [] }
async function loadHoles() { holes.value = (await api.listHoles()) || [] }

/**
 * 实时读取某赛事的开赛校验结果。每次都重新请求后端（后端同样实时读最新球洞版本），
 * 绝不复用页面上一次失败的旧结果。
 */
async function loadCheck(id, silent = false) {
  if (!id) return
  if (!silent) checkLoading.value = true
  try {
    const c = await api.tournamentStartCheck(id)
    startChecks.value = { ...startChecks.value, [id]: c }
  } catch (e) {
    if (!silent) error.value = e.message
  } finally {
    if (!silent) checkLoading.value = false
  }
}
async function loadChecksForOpenTournaments(silent = false) {
  await Promise.all(tournaments.value.filter(t => t.status === '报名中').map(t => loadCheck(t.id, silent)))
  // 已开始的赛事也取一份，用于展示「已开始」与冻结洞数（静默，避免闪烁）
  await Promise.all(tournaments.value.filter(t => t.status !== '报名中').map(t => loadCheck(t.id, true)))
}

async function loadAll() {
  await Promise.all([loadTournaments(), loadPlayers(), loadHoles(), loadCourses()])
  await Promise.all([loadLeaderboard(), loadScores(), loadChecksForOpenTournaments()])
}
async function loadPlayers() { players.value = (await api.listPlayers()) || [] }

async function refreshAfterMutation() {
  await loadTournaments()
  await Promise.all([
    loadLeaderboard(),
    loadScores(),
    loadHoles(),
    loadChecksForOpenTournaments(true)
  ])
}

function openTour() { tourForm.value = blankTour(); tourDialog.value = true; error.value = ''; tip.value = '' }
async function saveTour() {
  savingTour.value = true; error.value = ''
  try {
    const d = {
      ...tourForm.value,
      course: tourForm.value.courseId ? { id: Number(tourForm.value.courseId) } : null,
      courseRating: tourForm.value.courseRating === '' || tourForm.value.courseRating == null ? null : Number(tourForm.value.courseRating),
      slopeRating: tourForm.value.slopeRating === '' || tourForm.value.slopeRating == null ? null : Number(tourForm.value.slopeRating)
    }
    await api.createTournament(d)
    tip.value = `赛事「${tourForm.value.name}」已创建（报名中），开赛前会按绑定球场逐洞做可用性校验`
    tourDialog.value = false; await refreshAfterMutation()
  } catch (e) { error.value = e.message } finally { savingTour.value = false }
}

/**
 * 开赛：报名中 → 进行中。携带页面当前的赛事版本与球洞版本指纹：
 *  - 后端按<b>最新球洞版本</b>逐洞校验，通过才冻结本轮球洞并翻状态；
 *  - 被阻止（400，data 为逐洞明细）：赛事仍报名中，名单 / 草稿保留，面板渲染受影响洞号 / 状态 / 修复提示；
 *  - 并发开赛 / 版本过期 / 球洞刚被维护（409，data 为当前最新状态）：提示「数据已变化」并整体刷新；
 *  - 成功后进入「已开始」，后续把洞改成维护 / 封闭不再影响本轮。
 */
async function startTournament(t) {
  error.value = ''; tip.value = ''
  starting.value = true
  try {
    // 先取一次最新校验，保证使用最新球洞版本指纹（后端还会在锁内再校验一次）
    await loadCheck(t.id, true)
    const c = startChecks.value[t.id]
    await api.advanceTournament(t.id, '进行中', t.version, c ? c.holeVersionToken : null)
    tip.value = '球场可用性校验通过，赛事已开赛：本轮应打球洞已冻结，之后维护 / 封闭球洞不会改变本轮状态'
    await refreshAfterMutation()
  } catch (e) {
    if (e.isConflict) {
      error.value = `${e.message}（并发冲突，页面已按当前赛事与球洞状态刷新）`
    } else {
      error.value = e.message
    }
    // 400 被阻止 / 409 冲突：后端都可能带回最新 StartCheckView，直接渲染
    if (e.data && e.data.state) {
      startChecks.value = { ...startChecks.value, [t.id]: e.data }
    }
    await refreshAfterMutation()
  } finally { starting.value = false }
}

/** 结束（进行中 → 已结束）即收口：后端同事务校验全部参赛成绩、冻结唯一正式快照。 */
async function advance(t, target) {
  if (target === '进行中') return startTournament(t)
  error.value = ''; tip.value = ''
  const version = t.version
  try {
    await api.advanceTournament(t.id, target, version)
    tip.value = '赛事已收口：正式排行榜快照已生成并冻结，之后补录 / 修改 / 删除成绩都不会再改变名次'
    await refreshAfterMutation()
  } catch (e) {
    error.value = e.isConflict
      ? `${e.message}（并发冲突，页面已按当前状态刷新）`
      : e.message
    await loadTournaments()
    // 收口失败时后端会留下「生成失败」原因，读取排行榜即可展示具体赛事 / 球员 / 洞号
    await loadLeaderboard().catch(() => {})
  }
}

/** 显式「收口并生成正式排名」按钮，与推进到已结束同一路径。 */
const finalizing = ref(false)
async function finalizeLeaderboard() {
  if (!selectedTour.value) return
  finalizing.value = true; error.value = ''; tip.value = ''
  try {
    await api.finalizeTournamentLeaderboard(selectedTour.value.id, selectedTour.value.version)
    tip.value = '正式排行榜快照已生成并冻结，后续刷新只返回这份名次'
    await refreshAfterMutation()
  } catch (e) {
    error.value = e.isConflict ? `${e.message}（并发冲突）` : e.message
    await loadTournaments()
    await loadLeaderboard().catch(() => {})
  } finally { finalizing.value = false }
}

/** 重新校验：强制重新读取最新球洞版本（修复球洞后点此立即重判，不复用旧失败结果）。 */
async function recheck() {
  error.value = ''; tip.value = ''
  await loadCheck(selectedTourId.value)
  tip.value = '已按最新球洞版本重新校验'
}

/** 刷新排行榜：临时结果按当前成绩重算；正式快照只返回冻结体（名次不随后续成绩变化）。 */
const refreshingLb = ref(false)
async function refreshLeaderboard() {
  refreshingLb.value = true
  try { await Promise.all([loadLeaderboard(), loadCheck(selectedTourId.value, true)]); tip.value = '排行榜与开赛校验已刷新' }
  catch (e) { error.value = e.message }
  finally { refreshingLb.value = false }
}

function openScore() {
  scoreForm.value = blankScore(); scoreForm.value.tournamentId = selectedTourId.value
  scoreDialog.value = true; error.value = ''; tip.value = ''
}
async function saveScore() {
  savingScore.value = true; error.value = ''
  try {
    await api.recordScore({
      player: { id: Number(scoreForm.value.playerId) },
      tournament: { id: Number(scoreForm.value.tournamentId) },
      hole: { id: Number(scoreForm.value.holeId) },
      roundNo: Number(scoreForm.value.roundNo),
      strokes: Number(scoreForm.value.strokes)
    })
    tip.value = '成绩已录入（相对标准杆见 toPar 列），临时排名已随之更新'
    scoreDialog.value = false
    await Promise.all([loadScores(), loadLeaderboard()])
  } catch (e) { error.value = e.message } finally { savingScore.value = false }
}

/** 录入成绩时只能选赛事绑定球场的洞（与开赛校验 / 冻结口径一致）。 */
const scoreHoleOptions = computed(() => {
  const t = selectedTour.value
  if (!t || !t.course) return holes.value
  return holes.value.filter(h => h.course && h.course.id === t.course.id)
})

const toParText = (s) => s.toPar == null ? '-' : (s.toPar === 0 ? 'E' : (s.toPar > 0 ? '+' + s.toPar : s.toPar))
const toParRowText = (row) => (row.toPar == null ? '—' : (row.toPar === 0 ? 'E' : (row.toPar > 0 ? '+' + row.toPar : row.toPar)))
const lbTagType = (lb) => !lb ? 'info'
  : lb.kind === '正式快照' ? 'success'
  : lb.kind === '生成失败' ? 'danger'
  : lb.kind === '历史口径' ? 'info'
  : 'warning'
const lbTagText = (lb) => lb ? lb.kind : ''
const blockedTagType = (s) => s === '维护' ? 'warning' : s === '封闭' ? 'info' : 'danger'
const courseNameOf = (id) => courses.value.find(c => c.id === id)?.name || ''

// 自动刷新：选中赛事处于报名中 / 进行中时，每 10 秒静默重读开赛校验与排行榜：
// 其他人维护了球洞 / 开始了赛事 / 刷新了页面，都能及时看到最新状态；已结束停止轮询。
let timer = null
function syncAutoRefresh() {
  if (timer) { clearInterval(timer); timer = null }
  const t = selectedTour.value
  if (t && t.status !== '已结束') {
    timer = setInterval(() => {
      loadCheck(t.id, true).catch(() => {})
      if (activeTab.value === 'leaderboard') loadLeaderboard().catch(() => {})
    }, 10000)
  }
}

watch(selectedTourId, async () => {
  await Promise.all([loadLeaderboard(), loadScores(), loadCheck(selectedTourId.value)])
  syncAutoRefresh()
})
watch(() => selectedTour.value?.status, () => { syncAutoRefresh(); loadCheck(selectedTourId.value, true).catch(() => {}) })
watch(activeTab, syncAutoRefresh)

onMounted(async () => {
  await loadAll()
  syncAutoRefresh()
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div>
    <div v-if="error" class="err">{{ error }}</div>
    <div v-if="tip" class="ok-tip">{{ tip }}</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 赛事 ============ -->
      <el-tab-pane label="赛事" name="tournament">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">赛事 · 报名与推进</span>
            <span class="panel-desc">状态只能逐级推进：报名中 →（开赛前球场可用性校验）→ 进行中 → 已结束</span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 240px">
              <el-option v-for="t in tournaments" :key="t.id"
                         :label="`${t.name}（${t.status}）`" :value="t.id" />
            </el-select>
            <span class="spacer"></span>
            <el-button type="primary" @click="openTour">新建赛事</el-button>
          </div>

          <!-- ===== 开赛前球场可用性校验面板：校验中 / 可开赛 / 被阻止 / 已开始 ===== -->
          <el-card v-if="selectedTour" class="check-card" shadow="never">
            <div class="check-head">
              <span class="check-title">开赛前球场可用性校验 · {{ selectedTour.name }}</span>
              <el-tag :type="checkTagType" effect="dark" size="large">{{ checkState }}</el-tag>
              <span class="spacer"></span>
              <el-button size="small" :loading="checkLoading" @click="recheck">重新校验（读最新球洞）</el-button>
              <el-button size="small" type="primary" :loading="starting"
                         :disabled="!canStart(selectedTour)"
                         @click="startTournament(selectedTour)">开始赛事</el-button>
            </div>

            <!-- 校验中 -->
            <div v-if="checkState === '校验中'" v-loading="true" class="check-body muted">
              正在按赛事绑定球场读取最新球洞版本并逐洞校验，请稍候……
            </div>

            <template v-else-if="selectedCheck">
              <div class="check-meta">
                <el-tag size="small">绑定球场：{{ selectedCheck.courseName || '未绑定' }}</el-tag>
                <el-tag size="small" type="info">应打洞数：{{ selectedCheck.expectedHoleCount ?? '—' }}</el-tag>
                <el-tag size="small" type="success">可用洞：{{ selectedCheck.readyHoleNos.length }} 个
                  （{{ selectedCheck.readyHoleNos.length ? selectedCheck.readyHoleNos.join('、') : '无' }}）</el-tag>
                <el-tag size="small" type="info">赛事 v{{ selectedCheck.tournamentVersion }}</el-tag>
                <el-tag size="small" type="info">球洞版本指纹：{{ selectedCheck.holeVersionToken || '—' }}</el-tag>
                <span class="muted check-time">校验时间 {{ selectedCheck.checkedAt }}</span>
              </div>

              <!-- 可开赛 -->
              <el-alert v-if="checkState === '可开赛'" type="success" :closable="false" show-icon class="check-banner">
                <template #title>
                  全部 {{ selectedCheck.expectedHoleCount }} 个应打球洞均为「开放」且标准杆、码距合法，可以开赛。
                  点击「开始赛事」将在同一事务冻结本轮球洞并推进到进行中；多人同时开始时只有基于最新版本的一次成功。
                </template>
              </el-alert>

              <!-- 已开始 -->
              <el-alert v-else-if="checkState === '已开始'" type="warning" :closable="false" show-icon class="check-banner">
                <template #title>
                  赛事已为「{{ selectedCheck.tournamentStatus }}」：本轮应打球洞已于开赛时冻结
                  （{{ selectedCheck.readyHoleNos.length ? selectedCheck.readyHoleNos.join('、') : '—' }}）。
                  之后把任何球洞改为维护 / 封闭都<b>不会静默改变本轮状态</b>；但后续新建赛事开赛仍须重新逐洞校验。
                </template>
              </el-alert>

              <!-- 被阻止：逐洞列出受影响洞号 / 当前状态 / 修复提示 -->
              <template v-else-if="checkState === '已阻止'">
                <el-alert type="error" :closable="false" show-icon class="check-banner">
                  <template #title>
                    开赛被 {{ selectedCheck.blockedHoles.length }} 个球洞阻止，赛事仍保持「报名中」：
                    报名名单与成绩草稿都不会被清理，也不会留下半个进行中状态。请按下列提示修复后点「重新校验」。
                  </template>
                </el-alert>
                <el-table :data="selectedCheck.blockedHoles" border stripe size="small" class="block-table">
                  <el-table-column label="洞号" width="80">
                    <template #default="{ row }">
                      <span v-if="row.holeNo != null">第 {{ row.holeNo }} 洞</span>
                      <span v-else class="danger">—</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="当前状态" width="110">
                    <template #default="{ row }">
                      <el-tag :type="blockedTagType(row.status)" size="small">{{ row.status }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="标准杆" width="80">
                    <template #default="{ row }">{{ row.par ?? '—' }}</template>
                  </el-table-column>
                  <el-table-column label="码距" width="80">
                    <template #default="{ row }">{{ row.yardage ?? '—' }}</template>
                  </el-table-column>
                  <el-table-column prop="reason" label="阻止原因" min-width="200" />
                  <el-table-column prop="fixHint" label="修复提示" min-width="240" />
                </el-table>
                <div class="muted check-foot">
                  修复路径：左侧「球洞」模块 → 选择球场「{{ selectedCheck.courseName }}」→
                  维护洞状态为开放 / 补建未配置洞 / 修正标准杆与码距，然后回本页点「重新校验」。
                </div>
              </template>
            </template>
          </el-card>

          <el-table :data="tournaments" v-loading="loading" border stripe style="margin-top: 12px">
            <el-table-column prop="tournamentNo" label="编号" width="120" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="startDate" label="开始" width="110" />
            <el-table-column prop="endDate" label="结束" width="110" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === '报名中' ? 'info' : row.status === '进行中' ? 'warning' : 'success'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="绑定球场" width="120">
              <template #default="{ row }">
                <span v-if="row.course">{{ row.course.name }}</span>
                <el-tag v-else type="danger" size="small">未绑定</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="难度参数" width="120">
              <template #default="{ row }">
                <span v-if="row.courseRating != null">{{ row.courseRating }} / {{ row.slopeRating }}</span>
                <el-tag v-else type="danger" size="small">未维护</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="开赛校验" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.status !== '报名中'" type="warning" size="small">已开始</el-tag>
                <el-tag v-else-if="startChecks[row.id]?.state === '可开赛'" type="success" size="small">可开赛</el-tag>
                <el-tag v-else-if="startChecks[row.id]?.blocking" type="danger" size="small">
                  被阻止·{{ startChecks[row.id].blockedHoles.length }}
                </el-tag>
                <el-tag v-else type="info" size="small">校验中</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="warning" plain :disabled="!canStart(row)" @click="startTournament(row)">开始</el-button>
                <el-button size="small" type="success" plain :disabled="!canEnd(row)" @click="advance(row, '已结束')">结束</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 排行榜（临时结果 / 正式快照 / 收口失败） ============ -->
      <el-tab-pane label="排行榜" name="leaderboard">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">赛事排行榜 · 收口快照</span>
            <span class="panel-desc">报名中 / 进行中只显示临时结果；收口到已结束时冻结唯一正式快照，之后刷新永远是这份名次</span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 260px">
              <el-option v-for="t in tournaments" :key="t.id" :label="`${t.name}（${t.status}）`" :value="t.id" />
            </el-select>
            <el-tag :type="selectedTour ? (selectedTour.status === '已结束' ? 'success' : selectedTour.status === '进行中' ? 'warning' : 'info') : 'info'">
              赛事状态：{{ selectedTour?.status || '—' }}
            </el-tag>
            <span class="spacer"></span>
            <el-button :loading="refreshingLb" @click="refreshLeaderboard">刷新排行榜 / 校验</el-button>
            <el-button type="success" :disabled="!canEnd(selectedTour)" :loading="finalizing"
                       @click="finalizeLeaderboard">收口并生成正式排名</el-button>
          </div>

          <template v-if="leaderboard">
            <!-- 口径总览 -->
            <el-alert
              :type="leaderboard.kind === '正式快照' ? 'success'
                : leaderboard.kind === '生成失败' ? 'error'
                : leaderboard.kind === '历史口径' ? 'info' : 'warning'"
              :closable="false" show-icon class="lb-banner">
              <template #title>
                <div class="lb-banner-title">
                  <el-tag :type="lbTagType(leaderboard)" size="small" effect="dark">{{ lbTagText(leaderboard) }}</el-tag>
                  <span v-if="leaderboard.provisional">
                    当前为<b>临时结果</b>：仅按此刻已录入成绩实时计算，<b>不是正式成绩</b>，成绩补录 / 修改后会随时变化
                  </span>
                  <span v-else-if="leaderboard.frozen">
                    正式排名已于 {{ leaderboard.generatedAt }} 收口冻结，之后任何补录 / 修改 / 删除成绩都不再改变名次
                  </span>
                  <span v-else-if="leaderboard.legacy">
                    历史兼容口径：该赛事在本次修复前已结束、未生成正式快照，以下按旧成绩只读还原，仅用于历史查询
                  </span>
                  <span v-if="leaderboard.snapshotVersion != null" class="muted">
                    （赛事 v{{ leaderboard.tournamentVersion }} / 快照 v{{ leaderboard.snapshotVersion }}）
                  </span>
                </div>
              </template>
              <template v-if="leaderboard.kind === '生成失败'" #default>
                <div class="lb-fail-desc">
                  赛事「{{ leaderboard.tournamentName }}」收口失败，未产生正式结果，赛事仍是「{{ leaderboard.tournamentStatus }}」，
                  下方临时排名仍可查询；修复以下问题后可重新收口：
                </div>
              </template>
            </el-alert>

            <!-- 收口失败原因：精确到赛事 / 球员 / 洞号 -->
            <div v-if="leaderboard.failureReasons && leaderboard.failureReasons.length" class="lb-fail-box">
              <div class="lb-fail-head">正式排行榜生成失败原因（{{ leaderboard.failureReasons.length }} 条）：</div>
              <ul>
                <li v-for="(r, i) in leaderboard.failureReasons" :key="i">{{ r }}</li>
              </ul>
            </div>

            <!-- 临时结果下的非阻断提示（缺洞 / 越界 / 孤儿成绩等） -->
            <el-alert v-if="leaderboard.provisional && leaderboard.notices && leaderboard.notices.length"
                      type="warning" :closable="false" class="lb-banner">
              <template #title>临时排名提示（共 {{ leaderboard.notices.length }} 条，收口前须处理）</template>
              <ul class="lb-notice-list">
                <li v-for="(n, i) in leaderboard.notices" :key="i">{{ n }}</li>
              </ul>
            </el-alert>

            <!-- 名次表 -->
            <el-table :data="leaderboard.rows" border stripe class="lb-table">
              <el-table-column label="名次" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.rank != null" :type="row.rank === 1 ? 'success' : 'primary'">{{ row.rank }}</el-tag>
                  <span v-else class="muted">未完赛</span>
                </template>
              </el-table-column>
              <el-table-column prop="playerName" label="球员" min-width="120" />
              <el-table-column label="总杆" width="90">
                <template #default="{ row }">{{ row.totalStrokes ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="标准杆" prop="totalPar" width="90" />
              <el-table-column label="相对标准杆" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.toPar != null"
                          :type="row.toPar > 0 ? 'danger' : row.toPar < 0 ? 'success' : 'info'">{{ toParRowText(row) }}</el-tag>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="各轮 / 逐洞杆数" min-width="260">
                <template #default="{ row }">
                  <div v-for="rnd in row.rounds" :key="rnd.roundNo" class="lb-round">
                    <el-tag size="small" :type="rnd.complete ? '' : 'warning'">第{{ rnd.roundNo }}轮</el-tag>
                    <span class="lb-holes">
                      <template v-if="leaderboard.holeNos && leaderboard.holeNos.length">
                        <el-tag
                          v-for="h in leaderboard.holeNos" :key="h" size="small"
                          :type="rnd.strokesByHole[h] == null ? 'danger' : 'info'"
                          class="lb-hole">
                          {{ h }}洞:{{ rnd.strokesByHole[h] == null ? '缺' : rnd.strokesByHole[h] }}
                        </el-tag>
                      </template>
                      <span v-else class="muted">无应打洞</span>
                    </span>
                    <span class="muted">小计：{{ rnd.totalStrokes ?? '—' }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else description="暂无排行榜数据" />
        </div>
      </el-tab-pane>

      <!-- ============ 成绩 ============ -->
      <el-tab-pane label="成绩" name="score">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">成绩 · 按洞录入</span>
            <span class="panel-desc">只能录入赛事绑定球场的洞；单洞杆数须 ≥ 1，toPar = 杆数 − 标准杆（负为好）</span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 220px">
              <el-option v-for="t in tournaments" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <span class="spacer"></span>
            <el-tag v-if="selectedTour && selectedTour.status === '已结束'" type="danger" effect="plain">
              赛事已收口冻结，补录 / 修改成绩会被拒绝
            </el-tag>
            <el-button type="primary" :disabled="!!selectedTour && selectedTour.status === '已结束'"
                       @click="openScore">录入成绩</el-button>
          </div>
          <el-table :data="scores" border stripe>
            <el-table-column prop="holeNo" label="洞号" width="90" />
            <el-table-column prop="par" label="标准杆" width="90" />
            <el-table-column prop="playerName" label="球员" min-width="120" />
            <el-table-column prop="roundNo" label="轮次" width="90" />
            <el-table-column prop="strokes" label="杆数" width="90" />
            <el-table-column label="相对标准杆" width="120">
              <template #default="{ row }">
                <el-tag :type="row.toPar > 0 ? 'danger' : row.toPar < 0 ? 'success' : 'info'">{{ toParText(row) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建赛事 -->
    <el-dialog v-model="tourDialog" title="新建赛事" width="560px">
      <el-form :model="tourForm" label-width="110px" class="dialog-form">
        <el-form-item label="赛事编号"><el-input v-model="tourForm.tournamentNo" placeholder="如 T-2026-008" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="tourForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="tourForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="tourForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="tourForm.location" /></el-form-item>
        <el-form-item label="绑定球场">
          <el-select v-model="tourForm.courseId" placeholder="必选：开赛时按该球场逐洞校验" style="width: 100%">
            <el-option v-for="c in courses" :key="c.id" :label="`${c.name}（${c.holeCount} 洞）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="球场难度值"><el-input v-model="tourForm.courseRating" type="number" step="0.1" placeholder="courseRating，可后补" /></el-form-item>
        <el-form-item label="坡度难度"><el-input v-model="tourForm.slopeRating" type="number" placeholder="slopeRating 55~155，可后补" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="tourForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tourDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingTour" @click="saveTour">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑赛事（含球场绑定与难度参数补齐） -->
    <el-dialog v-model="editDialog" title="编辑赛事" width="560px">
      <el-form :model="editForm" label-width="110px" class="dialog-form">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="editForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="editForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="editForm.location" /></el-form-item>
        <el-form-item label="绑定球场">
          <el-select v-model="editForm.courseId" :disabled="editForm.status !== '报名中'" style="width: 100%">
            <el-option v-for="c in courses" :key="c.id" :label="`${c.name}（${c.holeCount} 洞）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="球场难度值">
          <el-input v-model="editForm.courseRating" type="number" step="0.1" placeholder="courseRating 20.0~80.0" />
        </el-form-item>
        <el-form-item label="坡度难度">
          <el-input v-model="editForm.slopeRating" type="number" placeholder="slopeRating 55~155" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="editForm.note" /></el-form-item>
      </el-form>
      <div class="muted" style="margin: -6px 0 8px 110px; font-size: 12px">
        球场绑定仅在「报名中」可改；开赛后本轮应打球洞冻结，维护 / 封闭球洞不影响本轮。
        难度参数需成对维护（都填或都留空）；补齐后可到差点中心定向重算。
      </div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 录入成绩 -->
    <el-dialog v-model="scoreDialog" title="录入成绩" width="560px">
      <el-form :model="scoreForm" label-width="110px" class="dialog-form">
        <el-form-item label="球员" class="full">
          <el-select v-model="scoreForm.playerId" filterable style="width: 100%">
            <el-option v-for="p in players" :key="p.id" :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="球洞">
          <el-select v-model="scoreForm.holeId" style="width: 100%"
                     :placeholder="selectedTour?.course ? `仅限「${selectedTour.course.name}」球洞` : '请先为赛事绑定球场'">
            <el-option v-for="h in scoreHoleOptions" :key="h.id"
                       :label="`第 ${h.holeNo} 洞 · par ${h.par} · ${h.status}`" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="轮次"><el-input v-model="scoreForm.roundNo" type="number" min="1" /></el-form-item>
        <el-form-item label="杆数" class="full"><el-input v-model="scoreForm.strokes" type="number" min="1" placeholder="须 ≥ 1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingScore" @click="saveScore">录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.check-card { margin-top: 12px; border-color: var(--el-border-color); }
.check-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.check-title { font-weight: 700; font-size: 14px; }
.check-body { padding: 10px 4px; display: flex; align-items: center; gap: 8px; }
.check-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.check-time { font-size: 12px; }
.check-banner { margin-bottom: 10px; }
.block-table { margin-bottom: 8px; }
.check-foot { font-size: 12px; line-height: 1.8; }
.danger { color: var(--el-color-danger); }
.lb-banner { margin-bottom: 10px; }
.lb-banner-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.lb-fail-desc { margin-top: 4px; font-size: 12px; }
.lb-fail-box {
  border: 1px solid var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 12px;
}
.lb-fail-head { font-weight: 700; color: var(--el-color-danger); margin-bottom: 6px; }
.lb-fail-box ul,
.lb-notice-list { margin: 0; padding-left: 18px; font-size: 12px; }
.lb-fail-box li { line-height: 1.8; }
.lb-table { margin-top: 4px; }
.lb-round { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 2px 0; }
.lb-holes { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.lb-hole { font-variant-numeric: tabular-nums; }
</style>
