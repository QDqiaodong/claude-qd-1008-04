<script setup>
import { ref, watch } from 'vue'
import api from '../api'

// 裁判逐洞裁决：仅争议洞可填裁决杆数与说明
const props = defineProps({
  card: { type: Object, required: true },
  refereeName: { type: String, default: '' }
})
const emit = defineEmits(['rule', 'back'])

const events = ref([])
watch(() => props.card.id, async (id) => { events.value = await api.scorecardEvents(id) }, { immediate: true })
function pretty(json) {
  if (!json) return '（无）'
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <el-button size="small" @click="emit('back')">← 返回争议队列</el-button>
      <span class="panel-title">
        {{ card.tournamentName }} · 第 {{ card.roundNo }} 轮 · {{ card.playerName }}
      </span>
      <el-tag type="danger">争议中</el-tag>
      <el-tag type="primary" effect="plain">当前版本 v{{ card.version }}</el-tag>
    </div>

    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px">
      裁决杆数与原杆数相同即「维持」，不同即「修正」；每个争议洞都必须写裁决说明。裁决后重新固化快照并发回球员确认。
    </el-alert>

    <el-table :data="card.holes" border stripe size="small">
      <el-table-column prop="holeNo" label="洞号" width="70" />
      <el-table-column prop="par" label="标准杆" width="80" />
      <el-table-column prop="strokes" label="原杆数" width="80" />
      <el-table-column label="球员争议理由" min-width="220">
        <template #default="{ row }">
          <el-text v-if="row.disputeReason" type="danger">{{ row.disputeReason }}</el-text>
          <span v-else class="panel-desc">无争议</span>
        </template>
      </el-table-column>
      <el-table-column label="裁决杆数（维持则填原杆数）" width="200">
        <template #default="{ row }">
          <el-input v-if="row.disputeReason"
            :model-value="row._draftRuled ?? row.strokes"
            @update:model-value="(v) => { row._draftRuled = v }"
            type="number" size="small" />
          <span v-else class="panel-desc">—</span>
        </template>
      </el-table-column>
      <el-table-column label="裁决说明" min-width="220">
        <template #default="{ row }">
          <el-input v-if="row.disputeReason" v-model="row._draftNote" size="small"
            :placeholder="`第 ${row.holeNo} 洞裁决说明（必填）`" />
          <span v-else class="panel-desc">—</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="toolbar" style="margin-top: 14px">
      <el-button type="danger" @click="emit('rule')">提交裁决 → 发回球员确认</el-button>
    </div>

    <el-timeline style="margin-top: 14px">
      <el-timeline-item v-for="e in events" :key="e.id"
        :type="e.action === '争议' ? 'danger' : 'primary'"
        :timestamp="`${e.createdAt} · ${e.actor} · v${e.beforeVersion ?? '—'} → v${e.afterVersion}`">
        <div><b>{{ e.action }}</b>：{{ e.detail }}</div>
        <div class="panel-desc">{{ e.beforeStatus }} → {{ e.afterStatus }}</div>
        <pre style="background:#f6f8f8;padding:8px;border-radius:6px;font-size:11px">{{ pretty(e.afterJson) }}</pre>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>
