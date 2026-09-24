<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/http'
import { useAuthStore, type Role } from '../stores/auth'

interface UserSummary { id: string; username: string; displayName: string; role: Role; enabled: boolean; mustChangePassword: boolean; failedLoginCount:number; lockedUntil?:string; lastLoginAt?: string; createdAt: string }
interface GrantItem { id:string; name:string; enabled:boolean; granted:boolean }
interface GrantCatalog { implicitAll:boolean; models:GrantItem[]; dataSources:GrantItem[]; knowledgeBases:GrantItem[] }
const users = ref<UserSummary[]>([]); const loading = ref(false); const dialog = ref(false); const saving = ref(false)
const temporaryPassword = ref(''); const keyword = ref(''); const auth = useAuthStore()
const grantsDialog=ref(false);const grantsLoading=ref(false);const grantUser=ref<UserSummary>();const grantCatalog=ref<GrantCatalog>();const selected=reactive({models:[] as string[],dataSources:[] as string[],knowledgeBases:[] as string[]})
const form = reactive({ username: '', displayName: '', role: 'USER' as Role })
const filtered = computed(() => users.value.filter(u => `${u.username}${u.displayName}`.toLowerCase().includes(keyword.value.toLowerCase())))

async function load() { loading.value = true; try { users.value = await api('/api/v1/admin/users') } finally { loading.value = false } }
async function create() {
  saving.value = true
  try {
    const result = await api<{ user: UserSummary; temporaryPassword: string }>('/api/v1/admin/users', { method: 'POST', body: JSON.stringify(form) })
    dialog.value = false; temporaryPassword.value = result.temporaryPassword; await load()
  } catch (e) { ElMessage.error(e instanceof ApiError ? e.message : '创建失败') } finally { saving.value = false }
}
async function toggle(user: UserSummary) {
  const action = user.enabled ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`${action}后，账号 ${user.username} ${user.enabled ? '将无法继续登录，现有登录状态会失效。' : '可以重新登录平台。'}`, `${action}账号？`, { type: 'warning', confirmButtonText: `确认${action}` })
    await api(`/api/v1/admin/users/${user.id}`, { method: 'PATCH', body: JSON.stringify({ enabled: !user.enabled }) }); await load(); ElMessage.success(`账号已${action}`)
  } catch (e) { if (e instanceof ApiError) ElMessage.error(e.message) }
}
async function reset(user: UserSummary) {
  try {
    await ElMessageBox.confirm(`重置 ${user.username} 的密码后，其现有登录状态会失效，下次登录必须修改密码。`, '重置密码？', { type: 'warning', confirmButtonText: '确认重置' })
    const result = await api<{ temporaryPassword: string }>(`/api/v1/admin/users/${user.id}/reset-password`, { method: 'POST' })
    temporaryPassword.value = result.temporaryPassword; await load()
  } catch (e) { if (e instanceof ApiError) ElMessage.error(e.message) }
}
async function openGrants(user:UserSummary){grantUser.value=user;grantsDialog.value=true;grantsLoading.value=true;try{const data=await api<GrantCatalog>(`/api/v1/admin/users/${user.id}/grants`);grantCatalog.value=data;selected.models=data.models.filter(x=>x.granted).map(x=>x.id);selected.dataSources=data.dataSources.filter(x=>x.granted).map(x=>x.id);selected.knowledgeBases=data.knowledgeBases.filter(x=>x.granted).map(x=>x.id)}finally{grantsLoading.value=false}}
async function saveGrants(){if(!grantUser.value)return;saving.value=true;try{await api(`/api/v1/admin/users/${grantUser.value.id}/grants`,{method:'PUT',body:JSON.stringify(selected)});grantsDialog.value=false;ElMessage.success('资源权限已更新')}catch(e){ElMessage.error(e instanceof ApiError?e.message:'保存失败')}finally{saving.value=false}}
async function unlock(user:UserSummary){try{await api(`/api/v1/admin/users/${user.id}/unlock`,{method:'POST'});await load();ElMessage.success('账号已解锁')}catch(e){ElMessage.error(e instanceof ApiError?e.message:'解锁失败')}}
async function forceLogout(user:UserSummary){try{await ElMessageBox.confirm(`将使 ${user.username} 的所有登录会话立即失效。`,'强制下线？',{type:'warning',confirmButtonText:'确认下线'});await api(`/api/v1/admin/users/${user.id}/force-logout`,{method:'POST'});ElMessage.success('用户已强制下线')}catch(e){if(e instanceof ApiError)ElMessage.error(e.message)}}
async function copyPassword() { await navigator.clipboard.writeText(temporaryPassword.value); ElMessage.success('临时密码已复制') }
onMounted(load)
</script>
<template>
  <section class="page-card">
    <div class="page-toolbar"><div><h2>账号</h2><p>创建、停用账号并重置临时密码</p></div><el-button type="primary" @click="dialog = true">新建账号</el-button></div>
    <div class="filter-row"><el-input v-model="keyword" clearable placeholder="搜索用户名或显示名称" style="width: 300px" /></div>
    <el-table v-loading="loading" :data="filtered">
      <el-table-column prop="username" label="用户名" min-width="140" /><el-table-column prop="displayName" label="显示名称" min-width="140" />
      <el-table-column label="角色" width="110"><template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}</template></el-table-column>
      <el-table-column label="状态" width="210"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag><el-tag v-if="row.mustChangePassword" type="warning" class="ml8">等待改密</el-tag><el-tag v-if="row.lockedUntil && new Date(row.lockedUntil)>new Date()" type="danger" class="ml8">已锁定</el-tag></template></el-table-column>
      <el-table-column label="最近登录" min-width="180"><template #default="{ row }">{{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString('zh-CN') : '尚未登录' }}</template></el-table-column>
      <el-table-column label="操作" width="390" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openGrants(row)">资源权限</el-button><el-button v-if="row.lockedUntil && new Date(row.lockedUntil)>new Date()" link type="warning" @click="unlock(row)">解锁</el-button><el-button link type="warning" :disabled="row.id === auth.user?.id" @click="forceLogout(row)">下线</el-button><el-button link type="primary" @click="reset(row)">重置密码</el-button><el-button link :type="row.enabled ? 'danger' : 'primary'" :disabled="row.id === auth.user?.id" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
  </section>
  <el-dialog v-model="dialog" title="新建账号" width="460px"><el-form label-position="top"><el-form-item label="用户名"><el-input v-model="form.username" placeholder="字母、数字、点、下划线或连字符" /></el-form-item><el-form-item label="显示名称"><el-input v-model="form.displayName" /></el-form-item><el-form-item label="角色"><el-radio-group v-model="form.role"><el-radio value="USER">普通用户</el-radio><el-radio value="ADMIN">管理员</el-radio></el-radio-group></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="create">创建账号</el-button></template></el-dialog>
  <el-dialog :model-value="!!temporaryPassword" title="临时密码已生成" width="460px" :close-on-click-modal="false" @close="temporaryPassword=''">
    <el-alert title="临时密码仅显示这一次，请通过安全渠道交付给用户。" type="warning" :closable="false" show-icon />
    <div class="temporary-password"><code>{{ temporaryPassword }}</code><el-button @click="copyPassword">复制</el-button></div>
    <template #footer><el-button type="primary" @click="temporaryPassword=''">我已妥善保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="grantsDialog" :title="`资源权限 · ${grantUser?.displayName ?? ''}`" width="680px"><div v-loading="grantsLoading"><el-alert v-if="grantCatalog?.implicitAll" title="管理员默认拥有全部已启用资源的访问权限。" type="info" :closable="false"/><template v-else><p class="grant-label">对话模型</p><el-checkbox-group v-model="selected.models" class="grant-grid"><el-checkbox v-for="item in grantCatalog?.models" :key="item.id" :value="item.id">{{ item.name }}<small v-if="!item.enabled">（未启用）</small></el-checkbox></el-checkbox-group><el-divider/><p class="grant-label">数据源</p><el-checkbox-group v-model="selected.dataSources" class="grant-grid"><el-checkbox v-for="item in grantCatalog?.dataSources" :key="item.id" :value="item.id">{{ item.name }}<small v-if="!item.enabled">（未启用）</small></el-checkbox></el-checkbox-group><el-divider/><p class="grant-label">知识库</p><el-checkbox-group v-model="selected.knowledgeBases" class="grant-grid"><el-checkbox v-for="item in grantCatalog?.knowledgeBases" :key="item.id" :value="item.id">{{ item.name }}<small v-if="!item.enabled">（未启用）</small></el-checkbox></el-checkbox-group></template></div><template #footer><el-button @click="grantsDialog=false">取消</el-button><el-button v-if="!grantCatalog?.implicitAll" type="primary" :loading="saving" @click="saveGrants">保存权限</el-button></template></el-dialog>
</template>
<style scoped>.grant-label{font-weight:600;color:#24324a}.grant-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 16px}.grant-grid small{color:#9aa1ad}</style>
