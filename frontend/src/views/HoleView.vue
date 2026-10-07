<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const courses = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const courseFilter = ref(null)
const statusFilter = ref('')
const courseDialog = ref(false)
const savingCourse = ref(false)
const courseForm = ref({ courseNo: '', name: '', holeCount: 9, note: '' })
const dialog = ref(false)
const saving = ref(false)
const statuses = ['开放', '维护', '封闭']
const parOptions = [3, 4, 5]

const form = ref(blank())
function blank() {
  return { courseId: null, holeNo: '', par: 4, yardage: '', status: '开放' }
}

async function loadCourses() {
  courses.value = (await api.listCourses()) || []
  if (!courseFilter.value && courses.value.length) {
    courseFilter.value = courses.value[0].id
  }
  if (!form.value.courseId && courses.value.length) {
    form.value.courseId = courseFilter.value || courses.value[0].id
  }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const p = { ...(courseFilter.value ? { courseId: courseFilter.value } : {}) }
    if (statusFilter.value) p.status = statusFilter.value
    const data = await api.listHoles(p)
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() {
  form.value = blank()
  if (courseFilter.value) form.value.courseId = courseFilter.value
  dialog.value = true; tip.value = ''; error.value = ''
}

function openCourse() {
  courseForm.value = { courseNo: '', name: '', holeCount: 9, note: '' }
  courseDialog.value = true; tip.value = ''; error.value = ''
}
async function saveCourse() {
  savingCourse.value = true; error.value = ''
  try {
    const c = await api.createCourse({
      courseNo: courseForm.value.courseNo.trim(),
      name: courseForm.value.name.trim(),
      holeCount: Number(courseForm.value.holeCount),
      note: courseForm.value.note
    })
    tip.value = `球场「${c.name}」已建档（应打 ${c.holeCount} 洞），请继续在该球场下补建球洞`
    courseDialog.value = false
    await loadCourses()
    courseFilter.value = c.id
    await load()
  } catch (e) { error.value = e.message } finally { savingCourse.value = false }
}
async function save() {
  saving.value = true; error.value = ''
  try {
    await api.createHole({
      course: { id: Number(form.value.courseId) },
      holeNo: Number(form.value.holeNo),
      par: Number(form.value.par),
      yardage: Number(form.value.yardage),
      status: form.value.status
    })
    tip.value = `球洞 ${form.value.holeNo} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

/**
 * 维护球洞状态：带上该行版本号做乐观并发闸门。
 * 两人同时维护同一球洞时，过期一方收 409「球洞数据已变化」并按最新版本刷新。
 */
const changing = ref({})
async function setStatus(row, target) {
  changing.value[row.id] = true; error.value = ''; tip.value = ''
  try {
    await api.setHoleStatus(row.id, target, row.version)
    tip.value = `球洞 ${row.holeNo} 已转为「${target}」`
    await load()
  } catch (e) {
    error.value = e.isConflict
      ? `${e.message}（并发冲突，球洞列表已按最新版本刷新）`
      : e.message
    await load()
  } finally { changing.value[row.id] = false }
}

const courseName = (id) => courses.value.find(c => c.id === id)?.name || (id ? `球场#${id}` : '未绑定')

onMounted(async () => {
  await loadCourses()
  await load()
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球洞 · 球场基础单元</span>
        <span class="panel-desc">球洞按球场隔离；标准杆只接受 3 / 4 / 5，码距 1~800；状态开放 / 维护 / 封闭（并发维护带版本校验）</span>
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
        <el-select v-model="courseFilter" placeholder="选择球场" style="width: 200px" @change="load">
          <el-option v-for="c in courses" :key="c.id"
                     :label="`${c.name}（应打 ${c.holeCount} 洞）`" :value="c.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button @click="openCourse">新建球场</el-button>
        <el-button type="primary" @click="openCreate">新建球洞</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column label="球场" min-width="130">
          <template #default="{ row }">{{ courseName(row.course?.id) }}</template>
        </el-table-column>
        <el-table-column prop="holeNo" label="洞号" width="80" />
        <el-table-column prop="par" label="标准杆" width="80" />
        <el-table-column prop="yardage" label="码距(码)" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '维护' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="changing[row.id]" :disabled="row.status === '开放'" @click="setStatus(row, '开放')">开放</el-button>
            <el-button size="small" :loading="changing[row.id]" :disabled="row.status === '维护'" @click="setStatus(row, '维护')">维护</el-button>
            <el-button size="small" type="danger" plain :loading="changing[row.id]" :disabled="row.status === '封闭'" @click="setStatus(row, '封闭')">封闭</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建球洞" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="所属球场" class="full">
          <el-select v-model="form.courseId" style="width: 100%">
            <el-option v-for="c in courses" :key="c.id"
                       :label="`${c.name}（应打 ${c.holeCount} 洞）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="洞号"><el-input v-model="form.holeNo" type="number" placeholder="1 ~ 18" /></el-form-item>
        <el-form-item label="标准杆">
          <el-select v-model="form.par" style="width: 100%">
            <el-option v-for="p in parOptions" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="码距(码)"><el-input v-model="form.yardage" type="number" placeholder="1 ~ 800" /></el-form-item>
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

    <el-dialog v-model="courseDialog" title="新建球场" width="520px">
      <el-form :model="courseForm" label-width="110px" class="dialog-form">
        <el-form-item label="球场编号"><el-input v-model="courseForm.courseNo" placeholder="如 C-C" /></el-form-item>
        <el-form-item label="球场名称"><el-input v-model="courseForm.name" /></el-form-item>
        <el-form-item label="应打洞数">
          <el-input v-model="courseForm.holeCount" type="number" min="1" max="18" placeholder="1 ~ 18" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="courseForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <div class="muted" style="margin: -6px 0 8px 110px; font-size: 12px">
        应打洞号为连续的 1 ~ 应打洞数；开赛校验要求这些洞全部配置为「开放」且 par / 码距合法。
      </div>
      <template #footer>
        <el-button @click="courseDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingCourse" @click="saveCourse">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
