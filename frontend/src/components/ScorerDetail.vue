<script setup>
import { ref, watch } from 'vue'
import api from '../api'

// 记分卡逐洞详情（记分员录草稿 / 球员确认与争议共用）
const props = defineProps({
  card: { type: Object, required: true },
  holes: { type: Array, default: () => [] }
})
const emit = defineEmits(['save-stroke', 'submit', 'confirm', 'dispute', 'back'])

const events = ref([])
const showHistory = ref(false)

watch(() => props.card.id, async (id) => {
  events.value = await api.scorecardEvents(id)
}, { immediate: true })

async function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value) events.value = await api.scorecardEvents(props.card.id)
}

function pretty(json) {
  if (!json) return '（无）'
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}

// 只展示球场开放洞 + 卡上已有的洞（封闭/维护洞若在卡上也露出来供提交校验拦截）
const visibleHoles = () => {
  const map = new Map()
  props.holes.forEach(h => map.set(h.holeNo, h))
  props.card.holes.forEach(h => {
    if (!map.has(h.holeNo)) map.set(h.holeNo, { holeNo: h.holeNo, par: h.par, status: '非开放' })
  })
  return [...map.values()].sort((a, b) => a.holeNo - b.holeNo)
}

const draftStroke = (holeNo) => props.card._draftStrokes?.[holeNo]
function setDraft(holeNo, v) {
  if (!props.card._draftStrokes) props.card._draftStrokes = {}
  props.card._draftStrokes[holeNo] = v
}

const isDraft = props.card.status === '草稿'
const isPending = props.card.status === '待球员确认'
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <el-button size="small" @click="emit('back')">← 返回列表</el-button>
      <span class="panel-title">
        {{ card.tournamentName }} · 第 {{ card.roundNo }} 轮 · {{ card.playerName }}
      </span>
      <el-tag :type="{ 草稿: 'info', 待球员确认: 'warning', 争议中: 'danger', 已签核: 'success' }[card.status]">
        {{ card.status }}
      </el-tag>
      <el-tag type="primary" effect="plain">当前版本 v{{ card.version }}</el-tag>
      <span v-if="card.totalStrokes != null" class="panel-desc">固化总杆 {{ card.totalStrokes }}</span>
    </div>

    <el-alert v-if="card.status !== '草稿'" type="info" :closable="false" show-icon style="margin-bottom: 10px">
      快照已固化：普通编辑无法改写。已签核为终态不可撤回；争议中的成绩不计入正式排行榜。
    </el-alert>

    <el-table :data="visibleHoles()" border stripe size="small">
      <el-table-column prop="holeNo" label="洞号" width="70" />
      <el-table-column prop="par" label="标准杆" width="80" />
      <el-table-column label="洞状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === '开放' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前杆数" width="200">
        <template #default="{ row }">
          <span v-if="!isDraft">{{ card.holes.find(h => h.holeNo === row.holeNo)?.strokes ?? '—' }}</span>
          <div v-else style="display:flex;gap:6px;align-items:center">
            <el-input :model-value="draftStroke(row.holeNo) ?? (card.holes.find(h => h.holeNo === row.holeNo)?.strokes ?? '')"
              @update:model-value="(v) => setDraft(row.holeNo, v)"
              @keyup.enter="emit('save-stroke', { holeNo: row.holeNo, strokes: Number(draftStroke(row.holeNo)) })"
              type="number" size="small" placeholder="≥1" />
            <el-button size="small" type="primary" plain
              @click="emit('save-stroke', { holeNo: row.holeNo, strokes: Number(draftStroke(row.holeNo)) })">
              保存
            </el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="争议" min-width="220">
        <template #default="{ row }">
          <template v-if="isPending">
            <el-input :model-value="card.holes.find(h => h.holeNo === row.holeNo)?._draftReason"
              @update:model-value="(v) => { const h = card.holes.find(x => x.holeNo === row.holeNo); if (h) h._draftReason = v }"
              size="small" placeholder="有异议请填写争议理由，留空表示无异议" />
          </template>
          <template v-else>
            <el-text v-if="card.holes.find(h => h.holeNo === row.holeNo)?.disputeReason" type="danger">
              {{ card.holes.find(h => h.holeNo === row.holeNo)?.disputeReason }}
            </el-text>
            <span v-else class="panel-desc">—</span>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="toolbar" style="margin-top: 14px">
      <el-button v-if="isDraft" type="warning" @click="emit('submit')">提交记分卡 → 待球员确认</el-button>
      <el-button v-if="isDraft" size="small" plain @click="emit('back')">先不提交，返回列表</el-button>
      <el-button v-if="isPending" type="success" @click="emit('confirm')">球员整卡确认（不可撤回）</el-button>
      <el-button v-if="isPending" type="danger" plain @click="emit('dispute')">就所填球洞提出争议</el-button>
      <span class="spacer"></span>
      <el-button size="small" @click="toggleHistory">{{ showHistory ? '收起' : '查看' }}提交/裁决留痕</el-button>
    </div>

    <el-collapse-transition>
      <div v-if="showHistory" style="margin-top: 10px">
        <el-timeline>
          <el-timeline-item v-for="e in events" :key="e.id"
            :type="e.action === '确认' ? 'success' : e.action === '争议' ? 'danger' : 'primary'"
            :timestamp="`${e.createdAt} · ${e.actor} · v${e.beforeVersion ?? '—'} → v${e.afterVersion}`">
            <div><b>{{ e.action }}</b>：{{ e.detail }}</div>
            <div class="panel-desc">{{ e.beforeStatus }} → {{ e.afterStatus }}</div>
            <el-collapse>
              <el-collapse-item title="操作前 / 操作后快照对比">
                <div style="display: flex; gap: 12px">
                  <pre style="flex:1;background:#f6f8f8;padding:8px;border-radius:6px;font-size:11px">{{ pretty(e.beforeJson) }}</pre>
                  <pre style="flex:1;background:#f0f9eb;padding:8px;border-radius:6px;font-size:11px">{{ pretty(e.afterJson) }}</pre>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-collapse-transition>
  </div>
</template>
