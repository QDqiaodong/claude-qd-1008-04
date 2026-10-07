<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const courses = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const courseFilter = ref(null)
const dialog = ref(false)
const saving = ref(false)
const statuses = ['开放', '维护', '封闭']
const parOptions = [3, 4, 5]

// ---- 球场建档 ----
const courseDialog = ref(false)
const savingCourse = ref(false)
const courseForm = ref(blankCourse())
function blankCourse() {
  return { courseNo: '', name: '', holeCount: 9, note: '' }
}

const form = ref(blank())
function blank() {
  return { courseId: null, holeNo: '', par: 4, yardage: '', status: '开放' }
}

async function loadCourses() {
  courses.value = (await api.listCourses()) || []
  if (!courseFilter.value && courses.value.length) courseFilter.value = courses.value[0].id
}

const selectedCourse = computed(() => courses.value.find(c => c.id === courseFilter.value) || null)

async function load() {
  loading.value = true; error.value = ''
  try {
    const p = { ...(statusFilter.value ? { status: statusFilter.value } : {}) }
    // 选中球场时按球场过滤（含全部状态，便于看到维护 / 封闭 / 缺口）
    if (courseFilter.value) p.courseId = courseFilter.value
    const data = await api.listHoles(p)
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadAll() {
  await loadCourses()
  await load()
}

function openCourse() { courseForm.value = blankCourse(); courseDialog.value = true; tip.value = ''; error.value = '' }
async function saveCourse() {
  savingCourse.value = true; error.value = ''
  try {
    const d = { ...courseForm.value, holeCount: Number(courseForm.value.holeCount) }
    const c = await api.createCourse(d)
    tip.value = `球场「${c.name}」已建档（${c.holeCount} 洞），可在其下新建球洞`
    courseDialog.value = false
    courseFilter.value = c.id
    await loadCourses()
    rows.value = []
  } catch (e) { error.value = e.message } finally { savingCourse.value = false }
}

function openCreate() {
  if (!courseFilter.value) { error.value = '请先选择 / 新建球场'; return }
  form.value = { ...blank(), courseId: courseFilter.value }
  dialog.value = true; tip.value = ''; error.value = ''
}
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      course: { id: Number(form.value.courseId) },
      holeNo: Number(form.value.holeNo),
      par: Number(form.value.par),
      yardage: Number(form.value.yardage),
      status: form.value.status
    }
    await api.createHole(d)
    tip.value = `第 ${d.holeNo} 洞已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

/**
 * 维护洞状态：携带页面上该洞的版本号。两人同时维护同一洞时，后提交者收到 409，
 * 提示「数据已变化」并自动按最新洞版本刷新列表，不允许静默覆盖。
 */
async function setStatus(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.setHoleStatus(row.id, target, row.version)
    tip.value = `第 ${row.holeNo} 洞已转为「${target}」${courseFilter.value ? '（赛事页开赛校验会实时反映该变化）' : ''}`
    await load()
  } catch (e) {
    error.value = e.isConflict ? `${e.message}（已按最新数据刷新）` : e.message
    await load()
  }
}

function courseName(row) {
  return courses.value.find(c => c.id === row.course?.id)?.name || row.course?.name || `球场#${row.course?.id}`
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">球洞 · 球场基础单元</span>
        <span class="panel-desc">球洞归属于球场；标准杆 3 / 4 / 5，码距 1~800；状态开放 / 维护 / 封闭；维护按洞版本并发控制</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">当前球洞数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">开放</div><div class="v">{{ rows.filter(r => r.status === '开放').length }}</div></div>
        <div class="stat"><div class="k">维护</div><div class="v">{{ rows.filter(r => r.status === '维护').length }}</div></div>
        <div class="stat"><div class="k">封闭</div><div class="v">{{ rows.filter(r => r.status === '封闭').length }}</div></div>
        <div class="stat" v-if="selectedCourse">
          <div class="k">应配置 / 缺口</div>
          <div class="v">{{ selectedCourse.holeCount }} / {{ Math.max(selectedCourse.holeCount - rows.length, 0) }}</div>
        </div>
      </div>

      <div class="toolbar">
        <el-select v-model="courseFilter" placeholder="选择球场" style="width: 200px" @change="load">
          <el-option v-for="c in courses" :key="c.id"
                     :label="`${c.name}（${c.holeCount} 洞）`" :value="c.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button @click="openCourse">新建球场</el-button>
        <el-button type="primary" @click="openCreate">新建球洞</el-button>
      </div>

      <el-alert v-if="selectedCourse" type="info" :closable="false" show-icon class="hole-course-tip">
        <template #title>
          当前球场：<b>{{ selectedCourse.name }}</b>，应配置 {{ selectedCourse.holeCount }} 个洞（1 ~ {{ selectedCourse.holeCount }}）。
          赛事开赛前系统逐洞校验：仅「开放 + 标准杆 / 码距合法」放行；
          <b style="color: var(--el-color-danger)">未建档的洞号按「未配置」阻止开赛</b>，维护 / 封闭洞同样阻止。球场版本 v{{ selectedCourse.version }}
        </template>
      </el-alert>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="holeNo" label="洞号" width="80" />
        <el-table-column label="所属球场" min-width="120">
          <template #default="{ row }">{{ courseName(row) }}</template>
        </el-table-column>
        <el-table-column prop="par" label="标准杆" width="80" />
        <el-table-column prop="yardage" label="码距(码)" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '维护' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="洞版本" width="80">
          <template #default="{ row }"><span class="muted">v{{ row.version }}</span></template>
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

    <el-dialog v-model="courseDialog" title="新建球场" width="520px">
      <el-form :model="courseForm" label-width="100px" class="dialog-form">
        <el-form-item label="球场编号"><el-input v-model="courseForm.courseNo" placeholder="如 C-C" /></el-form-item>
        <el-form-item label="球场名称"><el-input v-model="courseForm.name" placeholder="如 C场锦标场" /></el-form-item>
        <el-form-item label="应配置洞数">
          <el-input-number v-model="courseForm.holeCount" :min="1" :max="18" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="courseForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="courseDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingCourse" @click="saveCourse">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialog" title="新建球洞" width="520px">
      <el-form :model="form" label-width="100px" class="dialog-form">
        <el-form-item label="所属球场">
          <el-select v-model="form.courseId" style="width: 100%">
            <el-option v-for="c in courses" :key="c.id" :label="`${c.name}（${c.holeCount} 洞）`" :value="c.id" />
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
  </div>
</template>

<style scoped>
.hole-course-tip { margin: 10px 0; }
</style>
