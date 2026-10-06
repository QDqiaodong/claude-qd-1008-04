<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import HoleView from './views/HoleView.vue'
import TeeTimeView from './views/TeeTimeView.vue'
import PlayerView from './views/PlayerView.vue'
import TournamentView from './views/TournamentView.vue'
import ScorecardView from './views/ScorecardView.vue'
import HandicapView from './views/HandicapView.vue'

// ------------------------------------------------------------------
// 导航形态：左侧 SVG 球场平面图。球洞 / 开球台 / 球员会馆 / 赛事中心
// / 记分卡签核处 / 差点中心 是可点击热点，点击切换右侧业务模块（即改变 hash 路由）。
// 注意：main.js 未 app.use(router)，所以这里直接读 router.currentRoute.value。
// ------------------------------------------------------------------

const viewMap = {
  '/holes': HoleView,
  '/teetime': TeeTimeView,
  '/players': PlayerView,
  '/tournament': TournamentView,
  '/scorecard': ScorecardView,
  '/handicap': HandicapView
}

const hotspots = [
  { path: '/holes',      label: '球洞',       x: 70,  y: 92,  sub: '洞号·标准杆·码距' },
  { path: '/teetime',    label: '开球台',     x: 158, y: 58,  sub: '预订·时段' },
  { path: '/players',    label: '球员会馆',   x: 242, y: 120, sub: '差点·名册' },
  { path: '/tournament', label: '赛事中心',   x: 150, y: 268, sub: '排行榜·成绩' },
  { path: '/scorecard',  label: '记分卡签核', x: 250, y: 210, sub: '提交·确认·争议·收口' },
  { path: '/handicap',   label: '差点中心',   x: 60,  y: 268, sub: '指数·规则·重算' }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({ holes: 0, teetime: 0, players: 0, tournament: 0, scorecard: 0, handicap: 0 })

const currentView = computed(() => viewMap[currentPath.value] || HoleView)

async function loadCounts() {
  try {
    const [h, t, p, tour, hcp] = await Promise.all([
      api.listHoles(), api.listTeeTimes(), api.listPlayers(), api.listTournaments(), api.handicapSummary()
    ])
    // 记分卡热点默认统计进行中赛事的卡片数；没有进行中赛事则为 0
    const ongoing = tour.find(x => x.status === '进行中')
    const cards = ongoing ? await api.listScorecards(ongoing.id) : []
    counts.value = {
      holes: h.length, teetime: t.length, players: p.length,
      tournament: tour.length, scorecard: cards.length,
      handicap: (hcp || []).filter(x => x.status === '已计算').length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function countFor(path) {
  if (path === '/holes') return counts.value.holes
  if (path === '/teetime') return counts.value.teetime
  if (path === '/players') return counts.value.players
  if (path === '/tournament') return counts.value.tournament
  if (path === '/scorecard') return counts.value.scorecard
  if (path === '/handicap') return counts.value.handicap
  return 0
}

function go(path) {
  router.push(path)
  currentPath.value = path
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">⛳</span>
        <div class="brand-text">
          <div class="brand-title">薄荷绿高尔夫球场 · 运营管理系统</div>
          <div class="brand-sub">球洞 · 开球时间 · 球员 · 赛事 · 成绩</div>
        </div>
      </div>
    </header>

    <div class="body">
      <aside class="left-rail">
        <div class="rail-title">球场平面图 · 点击热点切换模块</div>
        <svg class="floorplan" viewBox="0 0 300 320" xmlns="http://www.w3.org/2000/svg">
          <!-- 场地底色 -->
          <rect x="6" y="6" width="288" height="308" rx="16" fill="#d6f5ee" stroke="#00bfa5" stroke-width="1.5" />
          <!-- 蜿蜒球道 -->
          <path d="M70 92 Q110 60 158 58 T242 120 Q210 200 150 268 Q90 220 70 92 Z"
                fill="#bdeee4" stroke="#9fdccf" stroke-width="2" />
          <!-- 水障碍 -->
          <ellipse cx="105" cy="196" rx="30" ry="17" fill="#aee0f2" stroke="#7fc9e6" stroke-width="1.5" />
          <text x="105" y="200" font-size="9" fill="#2b7a9b" text-anchor="middle">水障碍</text>
          <!-- 沙坑 -->
          <circle cx="182" cy="208" r="14" fill="#f3e6c4" stroke="#e0cd9b" stroke-width="1.5" />
          <text x="182" y="212" font-size="8" fill="#9c824a" text-anchor="middle">沙坑</text>
          <!-- 会所 -->
          <rect x="224" y="104" width="36" height="32" rx="4" fill="#fff" stroke="#00bfa5" stroke-width="1.5" />
          <text x="242" y="124" font-size="8" fill="#00897b" text-anchor="middle">会所</text>

          <!-- 热点：球洞 -->
          <g v-for="hs in hotspots" :key="hs.path"
             class="hotspot" :class="{ on: currentPath === hs.path }" @click="go(hs.path)">
            <circle class="hs-pulse" :cx="hs.x" :cy="hs.y" r="20" />
            <circle class="hs-dot" :cx="hs.x" :cy="hs.y" r="13" fill="#00bfa5" stroke="#fff" stroke-width="2" />
            <text class="hs-count" :x="hs.x" :y="hs.y + 4" text-anchor="middle">{{ countFor(hs.path) }}</text>
            <text class="hs-label" :x="hs.x" :y="hs.y - 22" text-anchor="middle">{{ hs.label }}</text>
            <text :x="hs.x" :y="hs.y + 34" font-size="9" fill="#6f8a85" text-anchor="middle">{{ hs.sub }}</text>
          </g>
        </svg>
        <div class="rail-hint">⛳ 平面图上的圆点即各业务模块入口，圆点内的数字为该模块记录数，点击即可跳转。</div>
      </aside>

      <main class="main">
        <component :is="currentView" />
      </main>
    </div>
  </div>
</template>
