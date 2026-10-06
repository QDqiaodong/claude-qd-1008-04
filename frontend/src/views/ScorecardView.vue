<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import ScorerDetail from '../components/ScorerDetail.vue'
import RefereeDetail from '../components/RefereeDetail.vue'

// ============================================================
// 赛事记分卡签核闭环
// 四个角色页签：记分员（草稿/提交）· 球员（确认/争议）· 裁判（裁决）· 收口与正式成绩
// 所有写操作都带 version；后端返回 409 时明确提示「数据已变化」并刷新当前状态。
// ============================================================

const tournaments = ref([])
const players = ref([])
const holes = ref([])
const entries = ref([])
const selectedTourId = ref(null)
const activeTab = ref('scorer')
const loading = ref(false)

// 记分员
const cards = ref([])
const scorerPlayerId = ref(null)
const scorerRoundNo = ref(1)
const scorerName = ref('记分员小周')
const activeCard = ref(null)

// 球员
const myPlayerId = ref(null)
const myCards = ref([])

// 裁判
const disputedCards = ref([])
const refereeName = ref('裁判长老')

// 收口
const signoff = ref(null)
const official = ref([])

const STATUS_TAG = {
  草稿: 'info',
  待球员确认: 'warning',
  争议中: 'danger',
  已签核: 'success'
}

const selectedTournament = computed(() => tournaments.value.find(t => t.id === selectedTourId.value))
const isOngoing = computed(() => selectedTournament.value?.status === '进行中')
const playerName = (id) => players.value.find(p => p.id === id)?.name || `#${id}`

// 应签核轮次 = 已签核 + 仍被阻止的（多轮赛事时两者之和才是全部名额×轮次）
const signoffTotalRounds = computed(() =>
  (signoff.value?.blockingReasons?.length || 0) + (signoff.value?.signedRounds || 0))

// ------------------------------------------------------------
// 基础加载
// ------------------------------------------------------------
async function loadBase() {
  [tournaments.value, players.value, holes.value] = await Promise.all([
    api.listTournaments(), api.listPlayers(), api.listHoles()
  ])
  // 默认选中「进行中」赛事，便于直接走签核流程
  if (!selectedTourId.value) {
    selectedTourId.value = tournaments.value.find(t => t.status === '进行中')?.id
      || tournaments.value[0]?.id || null
  }
}

async function loadEntries() {
  entries.value = selectedTourId.value ? await api.listEntries(selectedTourId.value) : []
}
async function loadCards() {
  cards.value = selectedTourId.value ? await api.listScorecards(selectedTourId.value) : []
}
async function loadDisputed() {
  disputedCards.value = await api.disputedScorecards(selectedTourId.value)
}
async function loadOfficial() {
  official.value = selectedTourId.value ? await api.officialResults(selectedTourId.value) : []
}
async function loadSignoff() {
  signoff.value = selectedTourId.value ? await api.signoffCheck(selectedTourId.value) : null
}

async function refreshCard(id) {
  activeCard.value = await api.getScorecard(id)
}

async function onTourChange() {
  activeCard.value = null
  await Promise.all([loadEntries(), loadCards(), loadDisputed(), loadOfficial(), loadSignoff()])
  if (activeTab.value === 'player' && myPlayerId.value) await loadMine()
}

onMounted(async () => {
  await loadBase()
  await onTourChange()
})

// ------------------------------------------------------------
// 通用：处理 409 版本冲突 —— 不静默覆盖，弹明确提示并刷新当前状态
// ------------------------------------------------------------
async function withConflictGuard(fn) {
  try {
    return await fn()
  } catch (e) {
    // 409：他人已先一步提交/确认/裁决。后端消息含当前状态/版本，这里再强制刷新，
    // 过期页面的结果绝不落库、不覆盖另一人。
    if (e.isConflict) {
      ElMessage({ type: 'error', duration: 6000, message: `数据已变化：${e.message}` })
    } else {
      ElMessage.error(e.message)
    }
    await Promise.all([loadCards(), loadDisputed(), loadSignoff(), loadOfficial()])
    if (myPlayerId.value) await loadMine().catch(() => {})
    if (activeCard.value) await refreshCard(activeCard.value.id).catch(() => { activeCard.value = null })
    throw e
  }
}

// ------------------------------------------------------------
// 记分员：取/建草稿、录洞、提交
// ------------------------------------------------------------
async function openDraft() {
  if (!scorerPlayerId.value) return ElMessage.warning('请先选择球员')
  try {
    loading.value = true
    activeCard.value = await api.draftScorecard(
      selectedTourId.value, Number(scorerPlayerId.value), Number(scorerRoundNo.value))
  } catch (e) {
    ElMessage.error(e.message)
    await loadEntries()
  } finally {
    loading.value = false
  }
}

async function openCard(card) {
  activeCard.value = await api.getScorecard(card.id)
  activeTab.value = 'scorer-detail'
}

async function saveStroke(payload) {
  const strokes = Number(payload.strokes)
  if (!strokes || strokes < 1) return ElMessage.warning(`第 ${payload.holeNo} 洞杆数须 ≥ 1`)
  try {
    await withConflictGuard(async () => {
      activeCard.value = await api.saveCardHole(activeCard.value.id, {
        holeNo: payload.holeNo, strokes, expectedVersion: activeCard.value.version
      })
      if (activeCard.value._draftStrokes) delete activeCard.value._draftStrokes[payload.holeNo]
      ElMessage.success(`第 ${payload.holeNo} 洞草稿已保存（新版本 v${activeCard.value.version}）`)
    })
  } catch (e) { /* 409 已提示 */ }
}

async function submitCard() {
  try {
    await ElMessageBox.confirm(
      '提交后本次逐洞成绩将固化为快照，普通编辑不能再改写；确认提交给球员确认？',
      '提交记分卡', { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '再看看' })
  } catch { return }
  try {
    await withConflictGuard(async () => {
      activeCard.value = await api.submitScorecard(activeCard.value.id, {
        submitter: scorerName.value || '记分员', expectedVersion: activeCard.value.version
      })
      ElMessage.success('已提交，进入待球员确认')
      await loadCards()
    })
  } catch (e) { /* 400/409 均已提示 */ }
}

// ------------------------------------------------------------
// 参赛名单
// ------------------------------------------------------------
async function addEntry() {
  const { value: playerId } = await ElMessageBox.prompt('请输入要登记的球员 ID', '登记参赛球员', {
    inputPattern: /^\d+$/, inputErrorMessage: '球员 ID 必须是数字'
  }).catch(() => ({}))
  if (!playerId) return
  try {
    await api.addEntry(selectedTourId.value, { playerId: Number(playerId), roundCount: 1 })
    ElMessage.success('参赛球员已登记')
    await loadEntries()
  } catch (e) { ElMessage.error(e.message) }
}

// ------------------------------------------------------------
// 球员：查看本人待确认卡、整卡确认、按洞争议
// ------------------------------------------------------------
async function loadMine() {
  if (!myPlayerId.value) { myCards.value = []; return }
  myCards.value = await api.myScorecards(Number(myPlayerId.value))
}
async function openMine(card) {
  activeCard.value = await api.getScorecard(card.id)
  activeTab.value = 'scorer-detail'
}

// 详情页返回：从球员入口进的回球员页，从裁判入口进的回裁判页，其余回记分员页
function backFromDetail() {
  activeCard.value = null
  activeTab.value = 'scorer'
}

async function confirmWholeCard() {
  try {
    await ElMessageBox.confirm('整卡确认后即为正式签核，签核不可撤回。确认无误？',
      '球员整卡确认', { type: 'warning', confirmButtonText: '确认整卡', cancelButtonText: '取消' })
  } catch { return }
  try {
    await withConflictGuard(async () => {
      activeCard.value = await api.confirmScorecard(activeCard.value.id, {
        playerName: activeCard.value.playerName, expectedVersion: activeCard.value.version
      })
      ElMessage.success('记分卡已正式签核')
      await Promise.all([loadCards(), loadMine(), loadSignoff(), loadOfficial()])
    })
  } catch (e) { /* noop */ }
}

async function raiseDispute() {
  const disputes = []
  for (const h of activeCard.value.holes) {
    const reason = h._draftReason
    if (reason && reason.trim()) disputes.push({ holeNo: h.holeNo, reason: reason.trim() })
  }
  if (!disputes.length) return ElMessage.warning('请至少在一个球洞填写争议理由')
  try {
    await withConflictGuard(async () => {
      activeCard.value = await api.disputeScorecard(activeCard.value.id, {
        playerName: activeCard.value.playerName,
        expectedVersion: activeCard.value.version, disputes
      })
      ElMessage.success('争议已提交，转裁判处理；争议期间不计入正式排行榜')
      await Promise.all([loadCards(), loadMine()])
    })
  } catch (e) { /* noop */ }
}

// ------------------------------------------------------------
// 裁判：维持 / 修正
// ------------------------------------------------------------
async function openDisputed(card) {
  activeCard.value = await api.getScorecard(card.id)
  activeTab.value = 'referee-detail'
}

function backFromReferee() {
  activeCard.value = null
  activeTab.value = 'referee'
  loadDisputed()
}

async function deliverRuling() {
  const rulings = activeCard.value.holes
    .filter(h => h.disputeReason)
    .map(h => ({
      holeNo: h.holeNo,
      ruledStrokes: Number(h._draftRuled ?? h.strokes),
      note: (h._draftNote || '').trim()
    }))
  if (!rulings.length) return ElMessage.warning('没有待裁决的争议洞')
  if (rulings.some(r => !r.ruledStrokes || r.ruledStrokes < 1)) {
    return ElMessage.error('裁决杆数须 ≥ 1（维持原成绩则填原杆数）')
  }
  if (rulings.some(r => !r.note)) return ElMessage.error('每个争议洞都必须填写裁决说明')
  try {
    await withConflictGuard(async () => {
      activeCard.value = await api.ruleScorecard(activeCard.value.id, {
        referee: refereeName.value || '裁判',
        expectedVersion: activeCard.value.version, rulings
      })
      ElMessage.success('裁决完成，记分卡已发回球员重新确认')
      await Promise.all([loadDisputed(), loadCards(), loadMine()])
    })
  } catch (e) { /* noop */ }
}

// ------------------------------------------------------------
// 收口与正式成绩
// ------------------------------------------------------------
async function closeTournament() {
  const check = await api.signoffCheck(selectedTourId.value)
  if (!check.canClose) {
    signoff.value = check
    return ElMessage.error(`仍有 ${check.blockingReasons.length} 条未签核记录，已列在下方，不能收口`)
  }
  try {
    await ElMessageBox.confirm('全部参赛球员记分卡均已签核，确认把赛事推进到「已结束」？',
      '赛事收口', { type: 'warning', confirmButtonText: '确认收口', cancelButtonText: '取消' })
  } catch { return }
  try {
    await api.advanceTournament(selectedTourId.value, '已结束')
    ElMessage.success('赛事已结束')
    await loadBase()
  } catch (e) { ElMessage.error(e.message) }
}

const toParText = (n) => n == null ? '-' : (n === 0 ? 'E' : (n > 0 ? `+${n}` : `${n}`))
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="selectedTourId" placeholder="选择赛事" style="width: 260px" @change="onTourChange">
        <el-option v-for="t in tournaments" :key="t.id"
          :label="`${t.name}（${t.status}）`" :value="t.id" />
      </el-select>
      <el-tag :type="isOngoing ? 'warning' : 'info'">
        {{ selectedTournament ? `赛事状态：${selectedTournament.status}` : '未选择赛事' }}
      </el-tag>
      <span class="spacer"></span>
      <el-button size="small" @click="onTourChange">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ================= 记分员 ================= -->
      <el-tab-pane label="记分员 · 草稿与提交" name="scorer">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">记分员工作台</span>
            <span class="panel-desc">
              按赛事/轮次/球员维护草稿；仅赛事进行中、应打（开放）洞都有合法杆数且无重复洞号时可提交，提交即固化快照
            </span>
          </div>

          <div class="toolbar">
            <el-select v-model="scorerPlayerId" filterable placeholder="选择球员" style="width: 200px">
              <el-option v-for="p in players" :key="p.id"
                :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
            </el-select>
            <el-input-number v-model="scorerRoundNo" :min="1" :max="4" controls-position="right" style="width: 120px" />
            <el-input v-model="scorerName" placeholder="记分员姓名" style="width: 160px" />
            <el-button type="primary" :loading="loading" @click="openDraft">打开 / 新建草稿</el-button>
            <span class="spacer"></span>
            <el-button size="small" @click="addEntry">登记参赛球员</el-button>
          </div>

          <el-table :data="entries" border size="small" style="margin-bottom: 12px"
            empty-text="该赛事暂无参赛球员（收口前必须全员签核）">
            <el-table-column type="index" label="序号" width="64" />
            <el-table-column label="参赛球员" min-width="160">
              <template #default="{ row }">{{ row.playerName }}（{{ row.playerNo }}）</template>
            </el-table-column>
            <el-table-column prop="roundCount" label="应打轮次" width="100" />
          </el-table>

          <el-table :data="cards" border stripe size="small" @row-click="openCard"
            :row-style="{ cursor: 'pointer' }">
            <el-table-column prop="roundNo" label="轮次" width="70" />
            <el-table-column label="球员" min-width="110">
              <template #default="{ row }">{{ playerName(row.playerId || row.player?.id) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="STATUS_TAG[row.status]">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="80">
              <template #default="{ row }"><el-tag type="primary" effect="plain">v{{ row.version }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="totalStrokes" label="快照总杆" width="90" />
            <el-table-column prop="lastSubmitter" label="最近提交/裁决" min-width="120" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 记分员/球员共用的逐洞详情 -->
      <el-tab-pane v-if="activeCard && ['scorer-detail', 'player'].includes(activeTab)"
        :label="`记分卡详情 #${activeCard.id}`" name="scorer-detail">
        <scorer-detail :card="activeCard" :holes="holes"
          @save-stroke="saveStroke" @submit="submitCard"
          @confirm="confirmWholeCard" @dispute="raiseDispute" @back="backFromDetail" />
      </el-tab-pane>

      <!-- ================= 球员 ================= -->
      <el-tab-pane label="球员 · 确认与争议" name="player">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">球员工作台</span>
            <span class="panel-desc">
              只能查看与处理本人的记分卡；可整卡确认（不可撤回），也可针对具体球洞填写争议；争议中成绩不计入正式排行榜
            </span>
          </div>
          <div class="toolbar">
            <el-select v-model="myPlayerId" filterable placeholder="我是（选择本人）" style="width: 220px"
              @change="loadMine">
              <el-option v-for="p in players" :key="p.id"
                :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
            </el-select>
            <el-button @click="loadMine">刷新我的记分卡</el-button>
          </div>
          <el-table :data="myCards" border stripe size="small" @row-click="openMine"
            :row-style="{ cursor: 'pointer' }">
            <el-table-column label="赛事" min-width="150">
              <template #default="{ row }">{{ row.tournamentName }}</template>
            </el-table-column>
            <el-table-column prop="roundNo" label="轮次" width="70" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }"><el-tag :type="STATUS_TAG[row.status]">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="80">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column prop="totalStrokes" label="快照总杆" width="90" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ================= 裁判 ================= -->
      <el-tab-pane label="裁判 · 争议裁决" name="referee">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">裁判工作台</span>
            <span class="panel-desc">
              只处理争议中的记分卡：逐洞维持原成绩或给出修正杆数并填写裁决说明，裁决后发回球员重新确认
            </span>
          </div>
          <div class="toolbar">
            <el-input v-model="refereeName" placeholder="裁判姓名" style="width: 180px" />
            <el-button @click="loadDisputed">刷新争议队列</el-button>
          </div>
          <el-table :data="disputedCards" border stripe size="small" @row-click="openDisputed"
            :row-style="{ cursor: 'pointer' }" empty-text="当前没有争议中的记分卡">
            <el-table-column label="赛事" min-width="150">
              <template #default="{ row }">{{ row.tournamentName }}</template>
            </el-table-column>
            <el-table-column prop="roundNo" label="轮次" width="70" />
            <el-table-column label="球员" min-width="110">
              <template #default="{ row }">{{ playerName(row.playerId || row.player?.id) }}</template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="80">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column prop="totalStrokes" label="当前总杆" width="90" />
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button size="small" type="danger" plain @click.stop="openDisputed(row)">去裁决</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 裁判逐洞裁决详情 -->
      <el-tab-pane v-if="activeCard && activeTab === 'referee-detail'" label="裁判裁决详情" name="referee-detail">
        <referee-detail :card="activeCard" :referee-name="refereeName"
          @rule="deliverRuling" @back="backFromReferee" />
      </el-tab-pane>

      <!-- ================= 收口与正式成绩 ================= -->
      <el-tab-pane label="收口 · 正式成绩 · 留痕" name="close">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">赛事收口检查与正式排行榜</span>
            <span class="panel-desc">
              赛事推进到已结束前，全部参赛球员每一轮记分卡都必须已签核；待确认 / 争议中 / 草稿 / 缺卡都会阻止收口
            </span>
          </div>

          <div class="toolbar">
            <el-button @click="Promise.all([loadSignoff(), loadOfficial()])">重新检查</el-button>
            <el-button type="success" :disabled="!isOngoing" @click="closeTournament">推进赛事到「已结束」</el-button>
            <span v-if="signoff" class="panel-desc">
              参赛名额 {{ signoff.entryCount }} · 已签核轮次 {{ signoff.signedRounds }} / {{ signoffTotalRounds }}
            </span>
          </div>

          <el-alert v-if="signoff && !signoff.canClose" type="error" :closable="false" show-icon
            title="存在未签核记录，赛事不能收口" style="margin-bottom: 12px">
            <ul style="margin: 6px 0; padding-left: 18px">
              <li v-for="(r, i) in signoff.blockingReasons" :key="i">{{ r }}</li>
            </ul>
          </el-alert>
          <el-alert v-else-if="signoff" type="success" :closable="false" show-icon
            title="全部参赛球员记分卡均已签核，可以收口" style="margin-bottom: 12px" />

          <div class="panel-title" style="margin: 8px 0">正式排行榜（只计已签核卡当前快照，历史版本不重复计入）</div>
          <el-table :data="official" border stripe size="small" empty-text="尚无已签核记分卡">
            <el-table-column type="index" label="名次" width="70" />
            <el-table-column prop="playerName" label="球员" min-width="120" />
            <el-table-column prop="roundNo" label="轮次" width="70" />
            <el-table-column prop="totalStrokes" label="总杆" width="80" />
            <el-table-column prop="totalPar" label="标准杆" width="80" />
            <el-table-column label="相对标准杆" width="110">
              <template #default="{ row }">
                <el-tag :type="row.toPar > 0 ? 'danger' : row.toPar < 0 ? 'success' : 'info'">
                  {{ toParText(row.toPar) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="快照版本" width="90">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
