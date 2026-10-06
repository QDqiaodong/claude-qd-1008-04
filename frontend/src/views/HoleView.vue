<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const dialog = ref(false)
const saving = ref(false)
const statuses = ['开放', '维护', '封闭']
const parOptions = [3, 4, 5]

const form = ref(blank())
function blank() {
  return { holeNo: '', par: 4, yardage: '', status: '开放' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listHoles(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = { ...form.value, holeNo: Number(form.value.holeNo), par: Number(form.value.par), yardage: Number(form.value.yardage) }
    await api.createHole(d)
    tip.value = `球洞 ${d.holeNo} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function setStatus(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.setHoleStatus(row.id, target)
    tip.value = `球洞 ${row.holeNo} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球洞 · 球场基础单元</span>
        <span class="panel-desc">标准杆只接受 3 / 4 / 5；状态开放 / 维护 / 封闭</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">球洞数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">开放</div><div class="v">{{ rows.filter(r => r.status === '开放').length }}</div></div>
        <div class="stat"><div class="k">维护</div><div class="v">{{ rows.filter(r => r.status === '维护').length }}</div></div>
        <div class="stat"><div class="k">封闭</div><div class="v">{{ rows.filter(r => r.status === '封闭').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建球洞</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="holeNo" label="洞号" width="90" />
        <el-table-column prop="par" label="标准杆" width="90" />
        <el-table-column prop="yardage" label="码距(码)" width="110" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '维护' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="row.status === '开放'" @click="setStatus(row, '开放')">开放</el-button>
            <el-button size="small" :disabled="row.status === '维护'" @click="setStatus(row, '维护')">维护</el-button>
            <el-button size="small" type="danger" plain :disabled="row.status === '封闭'" @click="setStatus(row, '封闭')">封闭</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建球洞" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="洞号"><el-input v-model="form.holeNo" type="number" placeholder="1 ~ 18" /></el-form-item>
        <el-form-item label="标准杆">
          <el-select v-model="form.par" style="width: 100%">
            <el-option v-for="p in parOptions" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="码距(码)"><el-input v-model="form.yardage" type="number" /></el-form-item>
        <el-form-item label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
