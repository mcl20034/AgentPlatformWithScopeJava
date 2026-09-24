<script setup lang="ts">
import { ChatDotRound, User, Fold, Expand, Setting, Coin, Collection, Monitor, TrendCharts } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const collapsed = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const title = computed(() => String(route.meta.title ?? (route.name === 'accounts' ? '账号管理' : route.name === 'chat' ? '智能问答' : '')))

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '64px' : '224px'" class="app-sidebar">
      <div class="brand"><div class="brand-mark">AI</div><span v-if="!collapsed">智能分析平台</span></div>
      <el-menu :default-active="route.path" router :collapse="collapsed" class="app-menu">
        <el-menu-item index="/chat"><el-icon><ChatDotRound /></el-icon><template #title>智能问答</template></el-menu-item>
        <template v-if="auth.user?.role === 'ADMIN'">
          <div v-if="!collapsed" class="menu-group">管理与配置</div>
          <el-menu-item index="/admin/accounts"><el-icon><User /></el-icon><template #title>账号管理</template></el-menu-item>
          <el-menu-item index="/admin/models"><el-icon><Setting /></el-icon><template #title>模型管理</template></el-menu-item>
          <el-menu-item index="/admin/embedding-models"><el-icon><Setting /></el-icon><template #title>Embedding 模型</template></el-menu-item>
          <el-menu-item index="/admin/data-sources"><el-icon><Coin /></el-icon><template #title>数据源管理</template></el-menu-item>
          <el-menu-item index="/admin/knowledge-bases"><el-icon><Collection /></el-icon><template #title>知识库</template></el-menu-item>
          <el-menu-item index="/admin/operations"><el-icon><Monitor /></el-icon><template #title>运行与审计</template></el-menu-item>
          <el-menu-item index="/admin/quality"><el-icon><TrendCharts /></el-icon><template #title>问答质量</template></el-menu-item>
        </template>
      </el-menu>
      <button class="collapse-button" @click="collapsed = !collapsed"><el-icon><Expand v-if="collapsed"/><Fold v-else/></el-icon><span v-if="!collapsed">收起导航</span></button>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <h1>{{ title }}</h1>
        <el-dropdown>
          <button class="user-button"><span class="avatar">{{ auth.user?.displayName.slice(0, 1) }}</span><span>{{ auth.user?.displayName }}</span></button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item @click="router.push('/change-password')">修改密码</el-dropdown-item><el-dropdown-item divided @click="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </el-header>
      <el-main class="app-main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>
