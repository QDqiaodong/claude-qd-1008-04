import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import HoleView from '../views/HoleView.vue'
import TeeTimeView from '../views/TeeTimeView.vue'
import PlayerView from '../views/PlayerView.vue'
import TournamentView from '../views/TournamentView.vue'
import ScorecardView from '../views/ScorecardView.vue'
import HandicapView from '../views/HandicapView.vue'

const routes = [
  { path: '/', redirect: '/holes' },
  { path: '/holes', name: 'holes', component: HoleView },
  { path: '/teetime', name: 'teetime', component: TeeTimeView },
  { path: '/players', name: 'players', component: PlayerView },
  { path: '/tournament', name: 'tournament', component: TournamentView },
  { path: '/scorecard', name: 'scorecard', component: ScorecardView },
  { path: '/handicap', name: 'handicap', component: HandicapView },
  { path: '/:pathMatch(.*)*', redirect: '/holes' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
