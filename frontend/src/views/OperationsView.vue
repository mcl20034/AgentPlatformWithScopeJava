<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '../api/http'

interface Summary { tasks: Record<string, number>; models: Record<string, number>; generatedAt: string }
interface Health { database:string; pgvector:string; enabledModels:number; enabledSources:number; enabledKnowledgeBases:number }
const summary=ref<Summary>();const health=ref<Health>();const tasks=ref<Record<string,unknown>[]>([]);const calls=ref<Record<string,unknown>[]>([]);const audits=ref<Record<string,unknown>[]>([]);const loading=ref(false)
function time(value:unknown){return value?new Date(String(value)).toLocaleString('zh-CN'):'—'}
async function load(){loading.value=true;try{[summary.value,health.value,tasks.value,calls.value,audits.value]=await Promise.all([api<Summary>('/api/v1/admin/operations/summary'),api<Health>('/api/v1/admin/operations/health'),api<Record<string,unknown>[]>('/api/v1/admin/operations/tasks?limit=100'),api<Record<string,unknown>[]>('/api/v1/admin/operations/model-calls?limit=100'),api<Record<string,unknown>[]>('/api/v1/admin/operations/audit?limit=100')])}catch(e){ElMessage.error(e instanceof ApiError?e.message:'运行数据加载失败')}finally{loading.value=false}}
onMounted(load)
</script>
<template>
  <div v-loading="loading" class="operations">
    <div class="ops-toolbar"><div><h2>运行与审计</h2><p>查看最近 24 小时的平台运行状态、模型调用和操作记录</p></div><el-button type="primary" @click="load">刷新</el-button></div>
    <div class="metric-grid">
      <el-card shadow="never"><span>任务总数</span><strong>{{ summary?.tasks.total ?? 0 }}</strong><small>完成 {{ summary?.tasks.completed ?? 0 }} · 失败 {{ summary?.tasks.failed ?? 0 }}</small></el-card>
      <el-card shadow="never"><span>运行中任务</span><strong>{{ summary?.tasks.active ?? 0 }}</strong><small>含排队与执行中</small></el-card>
      <el-card shadow="never"><span>模型调用</span><strong>{{ summary?.models.calls ?? 0 }}</strong><small>Token {{ summary?.models.tokens ?? 0 }}</small></el-card>
      <el-card shadow="never"><span>平均模型耗时</span><strong>{{ Math.round(summary?.models.average_duration_ms ?? 0) }} ms</strong><small>最近 24 小时</small></el-card>
    </div>
    <el-card shadow="never" class="block"><template #header><b>依赖与配置状态</b></template><div class="health-row"><el-tag :type="health?.database==='UP'?'success':'danger'">平台数据库 {{ health?.database }}</el-tag><el-tag :type="health?.pgvector==='UP'?'success':'warning'">pgvector {{ health?.pgvector }}</el-tag><span>启用模型 {{ health?.enabledModels ?? 0 }}</span><span>启用数据源 {{ health?.enabledSources ?? 0 }}</span><span>启用知识库 {{ health?.enabledKnowledgeBases ?? 0 }}</span></div></el-card>
    <el-tabs type="border-card" class="block">
      <el-tab-pane label="分析任务"><el-table :data="tasks" max-height="420"><el-table-column prop="created_at" label="提交时间" width="180"><template #default="s">{{ time(s.row.created_at) }}</template></el-table-column><el-table-column prop="username" label="用户" width="120"/><el-table-column prop="title" label="会话" min-width="220" show-overflow-tooltip/><el-table-column prop="status" label="状态" width="120"/><el-table-column prop="stage" label="阶段" width="180"/><el-table-column prop="error_code" label="错误码" min-width="180"/></el-table></el-tab-pane>
      <el-tab-pane label="模型调用"><el-table :data="calls" max-height="420"><el-table-column prop="occurred_at" label="调用时间" width="180"><template #default="s">{{ time(s.row.occurred_at) }}</template></el-table-column><el-table-column prop="provider" label="提供商" width="120"/><el-table-column prop="model_name" label="模型" min-width="150"/><el-table-column prop="purpose" label="用途" width="160"/><el-table-column prop="status" label="状态" width="100"/><el-table-column prop="duration_ms" label="耗时(ms)" width="110"/><el-table-column prop="total_tokens" label="Token" width="100"/><el-table-column prop="error_code" label="错误码" min-width="160"/></el-table></el-tab-pane>
      <el-tab-pane label="操作审计"><el-table :data="audits" max-height="420"><el-table-column prop="occurred_at" label="发生时间" width="180"><template #default="s">{{ time(s.row.occurred_at) }}</template></el-table-column><el-table-column prop="actor_username" label="操作者" width="120"/><el-table-column prop="action" label="动作" width="150"/><el-table-column prop="result" label="结果" width="100"/><el-table-column prop="ip_address" label="IP" width="140"/><el-table-column prop="request_id" label="请求 ID" min-width="220" show-overflow-tooltip/><el-table-column prop="details_json" label="详情" min-width="300" show-overflow-tooltip/></el-table></el-tab-pane>
    </el-tabs>
  </div>
</template>
<style scoped>
.operations{display:flex;flex-direction:column;gap:16px}.ops-toolbar{display:flex;justify-content:space-between;align-items:center}.ops-toolbar h2{margin:0 0 6px}.ops-toolbar p{margin:0;color:#7b8496}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(180px,1fr));gap:14px}.metric-grid span,.metric-grid small{display:block;color:#7b8496}.metric-grid strong{display:block;font-size:28px;margin:12px 0 8px;color:#17233c}.health-row{display:flex;align-items:center;gap:18px;flex-wrap:wrap}.block{border-radius:10px}@media(max-width:1100px){.metric-grid{grid-template-columns:repeat(2,1fr)}}
</style>
