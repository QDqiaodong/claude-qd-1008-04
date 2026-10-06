<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import api from '../api'

const tournaments = ref([])
const players = ref([])
const holes = ref([])

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
  return { tournamentNo: '', name: '', startDate: '2026-10-10', endDate: '2026-10-12', status: '报名中', location: '', courseRating: null, slopeRating: null, note: '' }
}

// ---- 赛事编辑（含球场难度参数补齐） ----
const editDialog = ref(false)
const savingEdit = ref(false)
const editForm = ref({})
function openEdit(t) {
  editForm.value = {
    id: t.id, name: t.name, startDate: t.startDate, endDate: t.endDate,
    location: t.location, courseRating: t.courseRating, slopeRating: t.slopeRating, note: t.note
  }
  editDialog.value = true; error.value = ''; tip.value = ''
}
async function saveEdit() {
  savingEdit.value = true; error.value = ''
  try {
    const d = {
      ...editForm.value,
      courseRating: editForm.value.courseRating === '' || editForm.value.courseRating == null ? null : Number(editForm.value.courseRating),
      slopeRating: editForm.value.slopeRating === '' || editForm.value.slopeRating == null ? null : Number(editForm.value.slopeRating)
    }
    await api.updateTournament(d.id, d)
    tip.value = `赛事「${d.name}」已更新${d.courseRating != null ? '，球场难度参数已补齐，可到差点中心定向重算受影响球员' : ''}`
    editDialog.value = false
    await loadTournaments()
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

async function loadAll() {
  await Promise.all([loadTournaments(), loadPlayers(), loadHoles()])
  await Promise.all([loadLeaderboard(), loadScores()])
}
async function loadPlayers() { players.value = (await api.listPlayers()) || [] }
async function loadHoles() { holes.value = (await api.listHoles()) || [] }

function openTour() { tourForm.value = blankTour(); tourDialog.value = true; error.value = ''; tip.value = '' }
async function saveTour() {
  savingTour.value = true; error.value = ''
  try {
    const d = {
      ...tourForm.value,
      courseRating: tourForm.value.courseRating === '' || tourForm.value.courseRating == null ? null : Number(tourForm.value.courseRating),
      slopeRating: tourForm.value.slopeRating === '' || tourForm.value.slopeRating == null ? null : Number(tourForm.value.slopeRating)
    }
    await api.createTournament(d)
    tip.value = `赛事「${tourForm.value.name}」已创建`
    tourDialog.value = false; await loadTournaments()
  } catch (e) { error.value = e.message } finally { savingTour.value = false }
}

/**
 * 推进赛事。开始（报名中 → 进行中）只翻状态；结束（进行中 → 已结束）即收口：
 * 后端在同一事务校验全部参赛成绩、冻结唯一正式快照。409（并发 / 版本过期）与 400
 * （收口校验失败）都会提示并按当前状态重新读取排行榜。
 */
async function advance(t, target) {
  error.value = ''; tip.value = ''
  const version = t.version
  try {
    await api.advanceTournament(t.id, target, version)
    tip.value = target === '已结束'
      ? '赛事已收口：正式排行榜快照已生成并冻结，之后补录 / 修改 / 删除成绩都不会再改变名次'
      : `赛事已推进至「${target}」`
    await loadTournaments()
    await Promise.all([loadLeaderboard(), loadScores()])
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
    await loadTournaments()
    await Promise.all([loadLeaderboard(), loadScores()])
  } catch (e) {
    error.value = e.isConflict ? `${e.message}（并发冲突）` : e.message
    await loadTournaments()
    await loadLeaderboard().catch(() => {})
  } finally { finalizing.value = false }
}

/** 刷新排行榜：临时结果按当前成绩重算；正式快照只返回冻结体（名次不随后续成绩变化）。 */
const refreshingLb = ref(false)
async function refreshLeaderboard() {
  refreshingLb.value = true
  try { await loadLeaderboard(); tip.value = '排行榜已刷新' }
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

const toParText = (s) => s.toPar == null ? '-' : (s.toPar === 0 ? 'E' : (s.toPar > 0 ? '+' + s.toPar : s.toPar))
const toParRowText = (row) => (row.toPar == null ? '—' : (row.toPar === 0 ? 'E' : (row.toPar > 0 ? '+' + row.toPar : row.toPar)))
const lbTagType = (lb) => !lb ? 'info'
  : lb.kind === '正式快照' ? 'success'
  : lb.kind === '生成失败' ? 'danger'
  : lb.kind === '历史口径' ? 'info'
  : 'warning'
const lbTagText = (lb) => lb ? lb.kind : ''

// 自动刷新：只要选中赛事还在报名中 / 进行中，每 10 秒重读排行榜（临时结果随成绩变化）；
// 已结束后冻结体不会变化，停止轮询，避免无意义请求。
let timer = null
function syncAutoRefresh() {
  if (timer) { clearInterval(timer); timer = null }
  const t = selectedTour.value
  if (t && t.status !== '已结束' && activeTab.value === 'leaderboard') {
    timer = setInterval(() => { loadLeaderboard().catch(() => {}) }, 10000)
  }
}

watch(selectedTourId, async () => {
  await Promise.all([loadLeaderboard(), loadScores()])
  syncAutoRefresh()
})
watch(() => selectedTour.value?.status, syncAutoRefresh)
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
            <span class="panel-desc">状态只能逐级推进：报名中 → 进行中 → 已结束</span>
          </div>
          <div class="toolbar">
            <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 220px">
              <el-option v-for="t in tournaments" :key="t.id" :label="`${t.name}（${t.status}）`" :value="t.id" />
            </el-select>
            <span class="spacer"></span>
            <el-button type="primary" @click="openTour">新建赛事</el-button>
          </div>
          <el-table :data="tournaments" v-loading="loading" border stripe>
            <el-table-column prop="tournamentNo" label="编号" width="130" />
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column prop="startDate" label="开始" width="120" />
            <el-table-column prop="endDate" label="结束" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === '报名中' ? 'info' : row.status === '进行中' ? 'warning' : 'success'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="location" label="地点" width="120" />
            <el-table-column label="难度参数" width="130">
              <template #default="{ row }">
                <span v-if="row.courseRating != null">{{ row.courseRating }} / {{ row.slopeRating }}</span>
                <el-tag v-else type="danger" size="small">未维护</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="warning" plain :disabled="!canStart(row)" @click="advance(row, '进行中')">开始</el-button>
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
            <el-button :loading="refreshingLb" @click="refreshLeaderboard">刷新排行榜</el-button>
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
                      <span v-else class="muted">无开放洞</span>
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
            <span class="panel-desc">单洞杆数须 ≥ 1，toPar = 杆数 − 标准杆（负为好）</span>
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
        <el-form-item label="赛事编号"><el-input v-model="tourForm.tournamentNo" placeholder="如 T-2026-003" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="tourForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="tourForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="tourForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="tourForm.location" /></el-form-item>
        <el-form-item label="球场难度值"><el-input v-model="tourForm.courseRating" type="number" step="0.1" placeholder="courseRating，可后补" /></el-form-item>
        <el-form-item label="坡度难度"><el-input v-model="tourForm.slopeRating" type="number" placeholder="slopeRating 55~155，可后补" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="tourForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tourDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingTour" @click="saveTour">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑赛事（含球场难度参数补齐） -->
    <el-dialog v-model="editDialog" title="编辑赛事" width="560px">
      <el-form :model="editForm" label-width="110px" class="dialog-form">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="开始日期"><el-date-picker v-model="editForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="editForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="地点"><el-input v-model="editForm.location" /></el-form-item>
        <el-form-item label="球场难度值">
          <el-input v-model="editForm.courseRating" type="number" step="0.1" placeholder="courseRating 20.0~80.0" />
        </el-form-item>
        <el-form-item label="坡度难度">
          <el-input v-model="editForm.slopeRating" type="number" placeholder="slopeRating 55~155" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="editForm.note" /></el-form-item>
      </el-form>
      <div class="muted" style="margin: -6px 0 8px 110px; font-size: 12px">
        难度参数需成对维护（都填或都留空）；补齐后请到「差点中心 → 缺参与定向重算」重算受影响球员。
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
          <el-select v-model="scoreForm.holeId" style="width: 100%">
            <el-option v-for="h in holes" :key="h.id" :label="`第 ${h.holeNo} 洞 · par ${h.par}`" :value="h.id" />
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
