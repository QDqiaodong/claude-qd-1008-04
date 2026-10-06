<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const players = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const dialog = ref(false)
const saving = ref(false)

const slotOptions = ['07:00', '07:10', '07:20', '08:00', '09:00', '10:00', '13:30', '15:00', '16:00']
const statuses = ['可订', '已订']

const form = ref(blank())
function blank() {
  return { playerId: '', playDate: '2026-10-04', slot: '07:00', status: '已订', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listTeeTimes(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadPlayers() {
  try { players.value = (await api.listPlayers()) || [] } catch (e) { players.value = [] }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      player: { id: Number(form.value.playerId) },
      playDate: form.value.playDate,
      slot: form.value.slot,
      status: form.value.status,
      note: form.value.note
    }
    await api.bookTeeTime(d)
    tip.value = `开球时间 ${d.playDate} ${d.slot} 预订成功`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function release(row) {
  error.value = ''; tip.value = ''
  try {
    await api.releaseTeeTime(row.id)
    tip.value = `${row.playerName} ${row.playDate} ${row.slot} 时段已释放`
    await load()
  } catch (e) { error.value = e.message }
}

onMounted(async () => { await loadPlayers(); await load() })
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">开球台 · 开球时间预订</span>
        <span class="panel-desc">同一球员同一天同一时段不可重复预订；可订 / 已订</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">预订数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">已订</div><div class="v">{{ rows.filter(r => r.status === '已订').length }}</div></div>
        <div class="stat"><div class="k">可订</div><div class="v">{{ rows.filter(r => r.status === '可订').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">预订开球时间</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="playDate" label="日期" width="120" />
        <el-table-column prop="slot" label="时段" width="90" />
        <el-table-column prop="playerNo" label="球员编号" width="120" />
        <el-table-column prop="playerName" label="球员" min-width="110" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '已订' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="warning" plain :disabled="row.status === '可订'" @click="release(row)">释放</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="预订开球时间" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="球员" class="full">
          <el-select v-model="form.playerId" filterable placeholder="选择球员" style="width: 100%">
            <el-option v-for="p in players" :key="p.id" :label="`${p.name}（${p.playerNo}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="打球日期"><el-date-picker v-model="form.playDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="开球时段">
          <el-select v-model="form.slot" style="width: 100%">
            <el-option v-for="s in slotOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">预订</el-button>
      </template>
    </el-dialog>
  </div>
</template>
