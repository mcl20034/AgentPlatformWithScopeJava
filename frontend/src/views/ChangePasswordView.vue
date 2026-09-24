<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false); const error = ref('')
const auth = useAuthStore(); const router = useRouter()
async function submit() {
  error.value = ''
  if (form.newPassword !== form.confirmPassword) { error.value = '两次输入的新密码不一致'; return }
  loading.value = true
  try { await auth.changePassword(form.currentPassword, form.newPassword); ElMessage.success('密码已修改'); await router.replace('/chat') }
  catch (e) { error.value = e instanceof ApiError ? e.message : '修改失败，请稍后重试' }
  finally { loading.value = false }
}
</script>
<template>
  <main class="auth-page">
    <section class="auth-card change-card">
      <div class="auth-logo shield">✓</div><h1>{{ auth.user?.mustChangePassword ? '首次登录安全设置' : '修改密码' }}</h1>
      <p class="auth-subtitle">{{ auth.user?.mustChangePassword ? '请设置自己的密码，完成前不能使用其他功能。' : '修改后，其他设备上的登录状态将失效。' }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form label-position="top">
        <el-form-item label="当前密码"><el-input v-model="form.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" @keyup.enter="submit" /></el-form-item>
        <div class="password-rules">12～72 位，需包含大写字母、小写字母、数字和特殊字符。</div>
        <el-button type="primary" size="large" class="full-button" :loading="loading" @click="submit">保存新密码</el-button>
      </el-form>
    </section>
  </main>
</template>
