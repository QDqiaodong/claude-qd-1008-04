<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const activeTab = ref('rules')
const error = ref('')
const tip = ref('')

// ---------------- 计算规则 ----------------
const rules = ref([])
const rulesLoading = ref(false)
const ruleDialog = ref(false)
const savingRule = ref(false)
const DEFAULT_TABLE = '3:1,4:1,5:1,6:2,7:2,8:2,9:3,10:3,11:3,12:4,13:4,14:4,15:5,16:5,17:5,18:6,19:6,20:8'
const ruleForm = ref(blankRule())
function blankRule() {
  return {
    ruleName: '', effectiveDate: '', roundsWindow: 20, minRounds: 3,
    bestCountTable: DEFAULT_TABLE, maxDifferential: 40.0, maxDrop: 5.0, note: ''
  }
}

async function loadRules() {
  rulesLoading.value = true
  try { rules.value = (await api.listHandicapRules()) || [] }
  catch (e) { error.value = e.message } finally { rulesLoading.value = false }
}
function openRule() { ruleForm.value = blankRule(); ruleDialog.value = true; error.value = ''; tip.value = '' }
async function saveRule() {
  savingRule.value = true; error.value = ''
  try {
    const d = {
      ...ruleForm.value,
      roundsWindow: Number(ruleForm.value.roundsWindow),
      minRounds: Number(ruleForm.value.minRounds),
      maxDifferential: Number(ruleForm.value.maxDifferential),
      maxDrop: Number(ruleForm.value.maxDrop)
    }
    await api.createHandicapRule(d)
    tip.value = `规则「${d.ruleName}」已创建，将作用于 ${d.effectiveDate} 之后（含）的计算`
    ruleDialog.value = false
    await loadRules()
  } catch (e) { error.value = e.message } finally { savingRule.value = false }
}
async function removeRule(rule) {
  error.value = ''; tip.value = ''
  try {
    await api.deleteHandicapRule(rule.id)
    tip.value = `规则「${rule.ruleName}」已删除`
    await loadRules()
  } catch (e) { error.value = e.message }
}

// ---------------- 批量重算 ----------------
const runs = ref([])
const runsLoading = ref(false)
const running = ref(false)
const runErrors = ref({})
const runForm = ref(blankRun())
function blankRun() {
  return { asOfDate: '2026-10-06', fromDate: '', toDate: '', batchSize: 20, triggeredBy: '管理员' }
}

async function loadRuns() {
  runsLoading.value = true
  try { runs.value = (await api.listHandicapRuns()) || [] }
  catch (e) { error.value = e.message } finally { runsLoading.value = false }
}
async function startRun() {
  running.value = true; error.value = ''; tip.value = ''
  try {
    const d = {
      asOfDate: runForm.value.asOfDate || null,
      fromDate: runForm.value.fromDate || null,
      toDate: runForm.value.toDate || null,
      batchSize: Number(runForm.value.batchSize) || 20,
      triggeredBy: runForm.value.triggeredBy
    }
    const run = await api.runHandicapRecalc(d)
    tip.value = `批次 ${run.runNo} 完成：成功 ${run.successCount}、样本不足 ${run.insufficientCount}、失败 ${run.failCount}`
    await loadRuns()
  } catch (e) { error.value = e.message } finally { running.value = false }
}
async function expandRun(row, expanded) {
  if (!expanded || expanded.length === 0 || runErrors.value[row.id]) return
  try { runErrors.value = { ...runErrors.value, [row.id]: (await api.handicapRunErrors(row.id)) || [] } }
  catch (e) { error.value = e.message }
}
function runStatusType(s) {
  if (s === '已完成') return 'success'
  if (s === '部分失败') return 'danger'
  return 'warning'
}

// ---------------- 缺参与定向重算 ----------------
const missing = ref([])
const missingLoading = ref(false)
const players = ref([])
const targetIds = ref([])
const targetAsOf = ref('2026-10-06')
const recalcing = ref(false)
const recalcResults = ref([])

async function loadMissing() {
  missingLoading.value = true
  try { missing.value = (await api.missingDifficulty()) || [] }
  catch (e) { error.value = e.message } finally { missingLoading.value = false }
}
async function loadPlayers() {
  try { players.value = (await api.listPlayers()) || [] } catch (e) { /* 静默 */ }
}
function selectAffected(m) {
  targetIds.value = m.affectedPlayers.map(p => p.playerId)
  tip.value = `已选中「${m.tournamentName}」的 ${targetIds.value.length} 名受影响球员，可直接发起定向重算`
}
async function recalcTargeted() {
  if (!targetIds.value.length) { error.value = '请先选择要重算的球员'; return }
  recalcing.value = true; error.value = ''; tip.value = ''
  try {
    recalcResults.value = (await api.recalcHandicaps({
      playerIds: targetIds.value, asOfDate: targetAsOf.value || null
    })) || []
    const ok = recalcResults.value.filter(r => r.ok).length
    tip.value = `定向重算完成：${ok} 人成功，${recalcResults.value.length - ok} 人失败`
    await loadMissing()
  } catch (e) { error.value = e.message } finally { recalcing.value = false }
}

const fmt = (n) => (n === null || n === undefined) ? '—' : Number(n).toFixed(1)

onMounted(async () => {
  await Promise.all([loadRules(), loadRuns(), loadMissing(), loadPlayers()])
})
</script>

<template>
  <div>
    <div v-if="error" class="err">{{ error }}</div>
    <div v-if="tip" class="ok-tip">{{ tip }}</div>

    <el-tabs v-model="activeTab">
      <!-- ============ 计算规则 ============ -->
      <el-tab-pane label="计算规则" name="rules">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">差点计算规则 · 按生效日期版本化</span>
            <span class="panel-desc">新规则只影响生效日之后（含）的计算；被引用的规则不可删除，历史指数按当时规则可追溯</span>
          </div>
          <div class="toolbar">
            <span class="muted">差值 = (113 / slope) × (总杆 − courseRating)，保留一位小数；指数取最低 K 个差值的平均</span>
            <span class="spacer"></span>
            <el-button type="primary" @click="openRule">新建规则</el-button>
          </div>
          <el-table :data="rules" v-loading="rulesLoading" border stripe>
            <el-table-column prop="ruleName" label="规则名称" min-width="140" />
            <el-table-column prop="effectiveDate" label="生效日期" width="110" />
            <el-table-column prop="roundsWindow" label="窗口(轮)" width="80" />
            <el-table-column prop="minRounds" label="最少样本" width="90" />
            <el-table-column label="样本→采用最低差值个数" min-width="220">
              <template #default="{ row }">
                <span class="mono">{{ row.bestCountTable }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="maxDifferential" label="单轮截断" width="90" />
            <el-table-column prop="maxDrop" label="单次下调≤" width="90" />
            <el-table-column label="引用" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.referenced" type="success" size="small">已引用</el-tag>
                <el-tag v-else type="info" size="small">未引用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="danger" plain :disabled="!!row.referenced"
                           :title="row.referenced ? '已被计算引用，为保证历史可追溯不能删除' : ''"
                           @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 批量重算 ============ -->
      <el-tab-pane label="批量重算" name="runs">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">全量重算 · 按赛事日期分批</span>
            <span class="panel-desc">只取基准日之前已结束的轮次；每个球员独立事务，单个失败不拖垮整批，失败明细留痕</span>
          </div>
          <div class="toolbar">
            <el-date-picker v-model="runForm.asOfDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="计算基准日" style="width: 150px" />
            <el-date-picker v-model="runForm.fromDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="赛事日期起(可空)" style="width: 150px" />
            <el-date-picker v-model="runForm.toDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="赛事日期止(可空)" style="width: 150px" />
            <el-input v-model="runForm.batchSize" type="number" min="1" max="500" style="width: 110px">
              <template #prepend>每批</template>
            </el-input>
            <el-input v-model="runForm.triggeredBy" placeholder="操作人" style="width: 120px" />
            <span class="spacer"></span>
            <el-button type="primary" :loading="running" @click="startRun">启动全量重算</el-button>
          </div>
          <el-table :data="runs" v-loading="runsLoading" border stripe row-key="id"
                    @expand-change="expandRun">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="run-errors">
                  <div v-if="!(runErrors[row.id] && runErrors[row.id].length)" class="muted">无失败明细</div>
                  <el-table v-else :data="runErrors[row.id]" size="small" border>
                    <el-table-column prop="batchLabel" label="批次" width="220" />
                    <el-table-column prop="playerName" label="球员" width="110">
                      <template #default="{ row: e }">{{ e.playerName || '（批次级）' }}</template>
                    </el-table-column>
                    <el-table-column prop="message" label="失败原因" min-width="240" />
                    <el-table-column prop="createdAt" label="时间" width="170" />
                  </el-table>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="runNo" label="批次号" width="190" />
            <el-table-column prop="asOfDate" label="基准日" width="105" />
            <el-table-column label="赛事日期范围" width="200">
              <template #default="{ row }">
                <span v-if="row.fromDate || row.toDate">{{ row.fromDate || '…' }} ~ {{ row.toDate || '…' }}</span>
                <span v-else class="muted">全部</span>
              </template>
            </el-table-column>
            <el-table-column prop="ruleName" label="采用规则" min-width="130" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="runStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalPlayers" label="球员" width="70" />
            <el-table-column prop="successCount" label="成功" width="70" />
            <el-table-column prop="insufficientCount" label="样本不足" width="90" />
            <el-table-column prop="failCount" label="失败" width="70">
              <template #default="{ row }">
                <span :class="{ 'delta-up': row.failCount > 0 }">{{ row.failCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="triggeredBy" label="操作人" width="90" />
            <el-table-column prop="finishedAt" label="完成时间" width="170" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 缺参与定向重算 ============ -->
      <el-tab-pane label="缺参与定向重算" name="missing">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">缺球场难度参数的赛事 · 明确缺项</span>
            <span class="panel-desc">这些赛事的已签核轮次在差点计算中被排除；到「赛事中心」补齐参数后，回来定向重算受影响球员</span>
          </div>
          <el-table :data="missing" v-loading="missingLoading" border stripe>
            <el-table-column prop="tournamentNo" label="赛事编号" width="120" />
            <el-table-column prop="tournamentName" label="赛事名称" min-width="140" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="endDate" label="结束日期" width="110" />
            <el-table-column label="缺项" width="180">
              <template #default="{ row }">
                <el-tag v-if="row.courseRating == null" type="danger" size="small" style="margin-right:6px">courseRating</el-tag>
                <el-tag v-if="row.slopeRating == null" type="danger" size="small">slopeRating</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="受影响球员" min-width="200">
              <template #default="{ row }">
                <span v-if="!row.affectedPlayers.length" class="muted">无已签核轮次</span>
                <span v-else>{{ row.affectedPlayers.map(p => p.playerName).join('、') }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain :disabled="!row.affectedPlayers.length"
                           @click="selectAffected(row)">选中受影响球员</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">定向重算</span>
            <span class="panel-desc">只重算选中球员（逐球员独立事务，失败逐个返回）；不影响其他球员的现有指数</span>
          </div>
          <div class="toolbar">
            <el-select v-model="targetIds" multiple filterable collapse-tags collapse-tags-tooltip
                       placeholder="选择要重算的球员" style="width: 420px">
              <el-option v-for="p in players" :key="p.id" :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
            </el-select>
            <el-date-picker v-model="targetAsOf" type="date" value-format="YYYY-MM-DD"
                            placeholder="计算基准日" style="width: 150px" />
            <span class="spacer"></span>
            <el-button type="primary" :loading="recalcing" @click="recalcTargeted">重算选中球员</el-button>
          </div>
          <el-table v-if="recalcResults.length" :data="recalcResults" border stripe size="small">
            <el-table-column prop="playerName" label="球员" width="120" />
            <el-table-column label="结果" width="100">
              <template #default="{ row }">
                <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '成功' : '失败' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="新指数" width="100">
              <template #default="{ row }">{{ row.indexValue == null ? '—' : fmt(row.indexValue) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="message" label="说明" min-width="220" />
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建规则 -->
    <el-dialog v-model="ruleDialog" title="新建差点计算规则" width="640px">
      <el-form :model="ruleForm" label-width="130px" class="dialog-form">
        <el-form-item label="规则名称"><el-input v-model="ruleForm.ruleName" placeholder="如 WHS标准口径v2" /></el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="ruleForm.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="最近轮次窗口"><el-input v-model="ruleForm.roundsWindow" type="number" min="1" /></el-form-item>
        <el-form-item label="最少样本轮次"><el-input v-model="ruleForm.minRounds" type="number" min="1" /></el-form-item>
        <el-form-item label="样本→采用个数" class="full">
          <el-input v-model="ruleForm.bestCountTable" type="textarea" :rows="2"
                    placeholder="样本轮数:采用个数，逗号分隔，如 3:1,4:1,...,20:8" />
        </el-form-item>
        <el-form-item label="单轮差值截断"><el-input v-model="ruleForm.maxDifferential" type="number" step="0.1" min="0" /></el-form-item>
        <el-form-item label="单次下调上限"><el-input v-model="ruleForm.maxDrop" type="number" step="0.1" min="0" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="ruleForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.run-errors { padding: 8px 12px; }
</style>
