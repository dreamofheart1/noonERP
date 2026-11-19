<template>
  <section>
    <el-alert type="info" :closable="false" show-icon title="站点有效值优先；失效或撤销后回退全局。参数按北京时间生效；自动审核规则暂未开放。" />
    <el-form inline class="rule-search" @submit.prevent>
      <el-form-item label="参数">
        <el-select v-model="key" :disabled="busy" aria-label="业务参数" filterable style="width: 280px" @change="resetHistory">
          <el-option v-for="item in keys" :key="item.key" :label="`${item.label} (${item.key})`" :value="item.key" />
        </el-select>
      </el-form-item>
      <el-form-item label="范围">
        <el-select v-model="scope" :disabled="busy" aria-label="参数范围" style="width: 150px" @change="resetHistory">
          <el-option label="全局 GLOBAL" value="GLOBAL" />
          <el-option label="阿联酋 AE" value="AE" />
          <el-option label="沙特 SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button @click="resetHistory" :loading="loading">刷新历史</el-button></el-form-item>
    </el-form>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="rule-error" />
    <el-table :data="rows" v-loading="loading" empty-text="该范围暂无版本">
      <el-table-column prop="version_no" label="版本" width="75" />
      <el-table-column prop="config_value" label="值" min-width="100" show-overflow-tooltip />
      <el-table-column prop="effective_from" label="生效时间（北京）" min-width="160" />
      <el-table-column prop="effective_to" label="结束时间（不含）" min-width="160" />
      <el-table-column label="撤销时间（北京）" min-width="160">
        <template #default="{ row }">{{ row.revoked_at || '未撤销' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button v-if="!row.revoked_at" link type="danger" :disabled="busy" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="rule-pages">
      <el-button :disabled="loading || busy || cursors.length === 1" @click="previousPage">上一页</el-button>
      <span>第 {{ cursors.length }} 页</span>
      <el-button :disabled="loading || busy || !hasMore" @click="nextPage">下一页</el-button>
    </div>

    <el-divider content-position="left">创建新版本</el-divider>
    <p>新版本不能与同范围的有效时段重叠；结束时间为空表示持续有效。撤销从当前北京时间生效。</p>
    <el-form label-position="top" :disabled="busy || loading" @submit.prevent>
      <el-form-item label="参数值" required>
        <el-select v-if="selected.choices" v-model="value" aria-label="参数值" placeholder="请选择参数值" style="width: 240px">
          <el-option v-for="option in selected.choices" :key="option" :label="option" :value="option" />
        </el-select>
        <el-input v-else v-model="value" aria-label="参数值" :placeholder="selected.hint || '请输入参数值'" style="width: 240px" maxlength="1024" />
      </el-form-item>
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12"><el-form-item label="生效时间（北京时间）" required>
          <el-date-picker v-model="from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" aria-label="生效时间" placeholder="选择生效时间" />
        </el-form-item></el-col>
        <el-col :xs="24" :sm="12"><el-form-item label="结束时间（北京时间，不含）">
          <el-date-picker v-model="to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" aria-label="结束时间" placeholder="不设置则持续有效" />
        </el-form-item></el-col>
      </el-row>
      <el-button type="primary" :loading="busy" :disabled="loading" @click="create">创建版本</el-button>
    </el-form>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createRule, revokeRule, ruleHistory } from '@/api/system/ruleConfig'

const keys = [
  { key: 'return_window_days', label: '退货窗口（天）', hint: '非负整数' },
  { key: 'appeal_window_days', label: '申诉窗口（天）', hint: '非负整数' },
  { key: 'statement_dispute_days', label: '账单争议窗口（天）', hint: '非负整数' },
  { key: 'api_max_destinations', label: 'Webhook 目标上限', hint: '非负整数' },
  { key: 'payout_threshold', label: '回款阈值', hint: '非负金额，最多四位小数' },
  { key: 'allocation_policy', label: '站点费用合并口径', choices: ['NONE', 'PARTIAL', 'FULL'] },
  { key: 'sku_allocation_enabled', label: 'SKU 分摊开关', choices: ['0', '1'] },
  { key: 'cost_method', label: '成本方法', choices: ['FIFO'] },
  { key: 'vat_mode', label: 'VAT 口径', choices: ['IGNORE'] },
  { key: 'return_restock_cost', label: '退货上架成本', choices: ['ORIGINAL'] },
  { key: 'stock_update_mode', label: '库存更新口径', choices: ['ABSOLUTE'] },
  { key: 'profit_allocation_basis', label: 'SKU 分摊方法', choices: ['REVENUE_SHARE'] },
  { key: 'order_idempotent_mode', label: '订单幂等方式', choices: ['FINGERPRINT_ACK'] }
]
const key = ref('return_window_days')
const scope = ref('GLOBAL')
const selected = computed(() => keys.find(item => item.key === key.value))
const value = ref('')
const from = ref(null)
const to = ref(null)
const rows = ref([])
const cursors = ref([0])
const hasMore = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const pageSize = 20
let requestId = 0

async function loadHistory() {
  const currentRequest = ++requestId
  const selectedKey = key.value
  const selectedScope = scope.value
  loading.value = true
  error.value = ''
  try {
    const response = await ruleHistory({ key: selectedKey, scope: selectedScope, afterId: cursors.value.at(-1), limit: pageSize + 1 })
    if (currentRequest !== requestId) return
    rows.value = response.data.slice(0, pageSize)
    hasMore.value = response.data.length > pageSize
  } catch {
    if (currentRequest === requestId) { rows.value = []; hasMore.value = false; error.value = '历史加载失败，请检查权限或稍后重试。' }
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}
function resetHistory() { cursors.value = [0]; value.value = ''; loadHistory() }
function nextPage() { if (hasMore.value && rows.value.length) { cursors.value.push(rows.value.at(-1).id); loadHistory() } }
function previousPage() { if (cursors.value.length > 1) { cursors.value.pop(); loadHistory() } }
async function create() {
  if (!value.value || !from.value) { error.value = '请输入参数值和生效时间。'; return }
  if (to.value && to.value <= from.value) { error.value = '结束时间必须晚于生效时间。'; return }
  busy.value = true
  error.value = ''
  try {
    await createRule({ key: key.value, scope: scope.value, value: value.value, effectiveFrom: from.value, effectiveTo: to.value || null })
    ElMessage.success('版本已创建')
    from.value = null; to.value = null
    resetHistory()
  } catch {
    error.value = '创建失败：请核对参数值、权限及生效时段是否重叠。'
  } finally { busy.value = false }
}
async function revoke(row) {
  try { await ElMessageBox.confirm('撤销从当前北京时间生效，历史版本会保留。确认撤销？', '撤销版本', { type: 'warning' }) }
  catch { return }
  busy.value = true
  error.value = ''
  try { await revokeRule(row.id); ElMessage.success('版本已撤销'); await loadHistory() }
  catch { error.value = '撤销失败：版本可能已撤销，请刷新后重试。' }
  finally { busy.value = false }
}
onMounted(loadHistory)
</script>

<style scoped>
.rule-search { margin-top: 18px; }
.rule-error { margin-bottom: 12px; }
.rule-pages { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
</style>
