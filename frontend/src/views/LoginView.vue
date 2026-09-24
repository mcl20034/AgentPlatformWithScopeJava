<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/http'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!username.value || !password.value) { error.value = '请输入用户名和密码'; return }
  loading.value = true; error.value = ''
  try {
    await auth.login(username.value, password.value)
    const target = auth.user?.mustChangePassword ? '/change-password' : String(route.query.redirect ?? '/chat')
    await router.replace(target)
  } catch (e) { error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <div class="auth-logo">AI</div>
      <h1>智能分析平台</h1><p class="auth-subtitle">使用组织分配的账号登录</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名"><el-input v-model="username" size="large" autocomplete="username" placeholder="请输入用户名" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入密码" @keyup.enter="submit" /></el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="full-button" @click="submit">登录</el-button>
      </el-form>
      <p class="auth-help">无法登录时，请联系平台管理员。</p>
    </section>
  </main>
</template>
