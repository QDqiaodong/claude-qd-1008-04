<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const summaries = ref({})
const error = ref('')
const tip = ref('')
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)

const form = ref(blank())
function blank() {
  return { playerNo: '', name: '', handicap: 10, phone: '', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const [players, hcp] = await Promise.all([api.listPlayers(), api.handicapSummary()])
    rows.value = players || []
    const map = {}
    for (const s of (hcp || [])) map[s.playerId] = s
    summaries.value = map
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = { ...form.value, handicap: Number(form.value.handicap) }
    await api.createPlayer(d)
    tip.value = `球员 ${d.name} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function statusType(h) {
  if (h <= 5) return 'success'
  if (h <= 18) return 'warning'
  return 'info'
}

// ---------------- 差点详情 ----------------
const hcpDialog = ref(false)
const hcpLoading = ref(false)
const recalcing = ref(false)
const hcp = ref(null)
const hcpHistory = ref([])

async function openHandicap(row) {
  hcpDialog.value = true
  await loadHandicap(row.id)
}
async function loadHandicap(playerId) {
  hcpLoading.value = true; error.value = ''
  try {
    const [view, history] = await Promise.all([
      api.playerHandicap(playerId), api.playerHandicapHistory(playerId)
    ])
    hcp.value = view
    hcpHistory.value = history || []
  } catch (e) { error.value = e.message } finally { hcpLoading.value = false }
}
async function recalcThis() {
  if (!hcp.value) return
  recalcing.value = true; error.value = ''; tip.value = ''
  try {
    const results = await api.recalcHandicaps({ playerIds: [hcp.value.playerId], asOfDate: null })
    const r = (results || [])[0]
    tip.value = r && r.ok
      ? `已重算：${hcp.value.playerName} 当前指数 ${r.indexValue == null ? '样本不足' : Number(r.indexValue).toFixed(1)}`
      : `重算失败：${r ? r.message : '未知错误'}`
    await loadHandicap(hcp.value.playerId)
    await load()
  } catch (e) { error.value = e.message } finally { recalcing.value = false }
}

const fmt = (n) => (n === null || n === undefined) ? '—' : Number(n).toFixed(1)
function changeText(c) {
  if (c === null || c === undefined) return '—'
  const v = Number(c)
  return (v > 0 ? '+' : '') + v.toFixed(1)
}
function changeClass(c) {
  if (c === null || c === undefined || Number(c) === 0) return 'muted'
  return Number(c) > 0 ? 'delta-up' : 'delta-down'
}
function hcpStatusType(s) {
  if (s === '已计算') return 'success'
  if (s === '样本不足') return 'warning'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球员会馆 · 球员名册</span>
        <span class="panel-desc">差点 handicap 必须落在 0 ~ 54 之间（USGA 上限）；差点指数由「差点中心」重算写回，依据可追溯</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">球员数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">单差点(≤5)</div><div class="v">{{ rows.filter(r => r.handicap <= 5).length }}</div></div>
        <div class="stat"><div class="k">中差点(6~18)</div><div class="v">{{ rows.filter(r => r.handicap > 5 && r.handicap <= 18).length }}</div></div>
        <div class="stat"><div class="k">高差点(>18)</div><div class="v">{{ rows.filter(r => r.handicap > 18).length }}</div></div>
        <div class="stat"><div class="k">已出指数</div><div class="v">{{ Object.values(summaries).filter(s => s.status === '已计算').length }}</div></div>
        <div class="stat"><div class="k">样本不足</div><div class="v">{{ Object.values(summaries).filter(s => s.status === '样本不足').length }}</div></div>
      </div>

      <div class="toolbar">
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建球员</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="playerNo" label="球员编号" width="110" />
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column prop="handicap" label="差点" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.handicap)">{{ row.handicap }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差点指数" width="100">
          <template #default="{ row }">
            <span v-if="row.handicapIndex != null" class="hcp-index">{{ fmt(row.handicapIndex) }}</span>
            <el-tag v-else-if="summaries[row.id]?.status === '样本不足'" type="warning" size="small">样本不足</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近变化" width="100">
          <template #default="{ row }">
            <span :class="changeClass(summaries[row.id]?.changeAmount)">
              {{ changeText(summaries[row.id]?.changeAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openHandicap(row)">差点详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建球员" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="球员编号"><el-input v-model="form.playerNo" placeholder="如 P-011" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="差点(0~54)"><el-input v-model="form.handicap" type="number" min="0" max="54" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <!-- 差点详情：当前指数 / 最近变化 / 采用与排除轮次 / 对应规则 / 历史追溯 -->
    <el-dialog v-model="hcpDialog" :title="`差点详情 · ${hcp?.playerName || ''}`" width="860px">
      <div v-loading="hcpLoading">
        <template v-if="hcp">
          <div class="stat-row">
            <div class="stat"><div class="k">当前指数</div>
              <div class="v">{{ hcp.status === '已计算' ? fmt(hcp.currentIndex) : '—' }}</div></div>
            <div class="stat"><div class="k">状态</div>
              <div class="v"><el-tag :type="hcpStatusType(hcp.status)">{{ hcp.status }}</el-tag></div></div>
            <div class="stat"><div class="k">最近变化</div>
              <div class="v" :class="changeClass(hcp.changeAmount)">{{ changeText(hcp.changeAmount) }}</div></div>
            <div class="stat"><div class="k">采用 / 样本</div>
              <div class="v">{{ hcp.usedCount ?? '—' }} / {{ hcp.sampleSize ?? '—' }}</div></div>
            <div class="stat"><div class="k">手工差点</div>
              <div class="v">{{ hcp.baseHandicap }}</div></div>
          </div>

          <div v-if="hcp.status !== '未计算'" class="hcp-meta">
            <span>规则：<b>{{ hcp.ruleName }}</b>（{{ hcp.ruleEffectiveDate }} 起生效）</span>
            <span>基准日：{{ hcp.asOfDate }}</span>
            <span>计算时间：{{ hcp.calculatedAt }}</span>
            <el-tag v-if="hcp.dropLimited" type="danger" size="small">下调受限</el-tag>
            <el-tag v-if="hcp.missingParams" type="warning" size="small">含缺参轮次</el-tag>
          </div>
          <div v-if="hcp.remark" class="muted" style="margin-bottom:10px">{{ hcp.remark }}</div>
          <div v-if="hcp.status === '未计算'" class="muted" style="margin-bottom:10px">
            该球员还没有差点计算记录，点击右下角「重算该球员」或到差点中心发起全量重算。
          </div>

          <template v-if="hcp.rounds && hcp.rounds.length">
            <div class="section-title">采用与排除的轮次（最近窗口内）</div>
            <el-table :data="hcp.rounds" size="small" border stripe max-height="260">
              <el-table-column prop="tournamentName" label="赛事" min-width="120" />
              <el-table-column prop="playDate" label="日期" width="105" />
              <el-table-column prop="roundNo" label="轮次" width="60" />
              <el-table-column prop="grossScore" label="总杆" width="60" />
              <el-table-column label="难度参数" width="110">
                <template #default="{ row }">
                  <span v-if="row.courseRating != null">{{ row.courseRating }} / {{ row.slopeRating }}</span>
                  <el-tag v-else type="danger" size="small">缺参数</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="差值" width="80">
                <template #default="{ row }">{{ row.differential == null ? '—' : fmt(row.differential) }}</template>
              </el-table-column>
              <el-table-column label="结果" min-width="180">
                <template #default="{ row }">
                  <el-tag v-if="row.used" type="success" size="small">采用</el-tag>
                  <el-tag v-else type="info" size="small">排除</el-tag>
                  <el-tag v-if="row.capped" type="warning" size="small" style="margin-left:4px">已截断</el-tag>
                  <span v-if="row.exclusionReason" class="muted" style="margin-left:6px">{{ row.exclusionReason }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="hcpHistory.length">
            <div class="section-title">历史计算（按当时规则可追溯）</div>
            <el-table :data="hcpHistory" size="small" border stripe max-height="220">
              <el-table-column prop="calculatedAt" label="计算时间" width="165" />
              <el-table-column prop="asOfDate" label="基准日" width="100" />
              <el-table-column prop="ruleName" label="规则" min-width="120" />
              <el-table-column label="指数" width="80">
                <template #default="{ row }">{{ row.indexValue == null ? '—' : fmt(row.indexValue) }}</template>
              </el-table-column>
              <el-table-column label="变化" width="80">
                <template #default="{ row }">
                  <span :class="changeClass(row.changeAmount)">{{ changeText(row.changeAmount) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="hcpStatusType(row.status)" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
            </el-table>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="hcpDialog = false">关闭</el-button>
        <el-button type="primary" :loading="recalcing" :disabled="!hcp" @click="recalcThis">重算该球员</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hcp-index { font-weight: 800; color: var(--primary-deep); font-size: 15px; }
.hcp-meta { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.section-title { font-size: 13px; font-weight: 700; color: var(--primary-deep); margin: 12px 0 8px; }
</style>
