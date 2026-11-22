<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 头程费用本页仅登记（allocationFlag 为标记），实际分摊与利润接入未实现`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />
  <el-alert title="基础流程：SKU 管理 → 采购单 → ASN 入仓 → 头程费用。新增费用可多选关联当前站点的采购单（跨站/不存在将被拒绝），并与 ASN 关联并存。" type="warning" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="站点">
        <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
      </el-form-item>
      <el-form-item label="费用类型" prop="feeType">
        <el-select v-model="queryParams.feeType" placeholder="全部" clearable style="width: 150px">
          <el-option v-for="t in feeTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="期间" prop="period">
        <el-input v-model="queryParams.period" placeholder="YYYY-MM" clearable style="width: 110px" />
      </el-form-item>
      <el-form-item label="ASN ID" prop="asnId">
        <el-input v-model="queryParams.asnId" placeholder="可选" clearable style="width: 120px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" size="mini" v-hasPermi="['noon:inventory:shipment:create']" @click="handleCreate">新增费用</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="ID" prop="id" width="80" />
      <el-table-column label="费用单号" prop="costNo" min-width="170" />
      <el-table-column label="站点" prop="siteCode" width="70" />
      <el-table-column label="ASN" prop="asnId" width="80" />
      <el-table-column label="关联采购单" width="120">
        <template #default="scope">
          <el-tag v-if="scope.row.poIds && scope.row.poIds.length" size="small" type="success" effect="plain">{{ scope.row.poIds.length }} 单</el-tag>
          <span v-else style="color:#909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="期间" prop="period" width="90" />
      <el-table-column label="类型" prop="feeType" width="110" />
      <el-table-column label="金额" prop="amount" width="110" />
      <el-table-column label="币种" prop="currency" width="70" />
      <el-table-column label="汇率" prop="fxRate" width="100" />
      <el-table-column label="分摊标记" prop="allocationFlag" width="100" />
      <el-table-column label="登记时间" prop="createdAt" width="160" />
      <el-table-column label="操作" align="center" width="90" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-button link type="primary" size="mini" v-hasPermi="['noon:inventory:shipment:list']" @click="handleDetail(scope.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <!-- 新建费用弹窗 -->
    <el-dialog title="新增头程费用" v-model="createOpen" width="560px" append-to-body>
      <el-form ref="createFormRef" :model="createForm" label-width="100px" size="small">
        <el-form-item label="站点" required>
          <el-select v-model="createForm.siteCode" placeholder="请选择授权站点" style="width: 100%">
            <el-option v-for="s in siteOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="ASN ID" prop="asnId">
          <el-input v-model="createForm.asnId" placeholder="可选，留空表示不挂 ASN" type="number" />
        </el-form-item>
        <el-form-item label="关联采购单">
          <el-select v-model="createForm.poIds" multiple filterable clearable collapse-tags
                     placeholder="可多选，仅当前站点采购单" style="width: 100%" :loading="poLoading">
            <el-option v-for="po in poOptions" :key="po.id" :label="poOrderLabel(po)" :value="po.id" />
          </el-select>
          <div style="font-size:12px;color:#909399;line-height:1.4">可多选；跨站或不存在的采购单将被拒绝。</div>
        </el-form-item>
        <el-form-item label="期间" prop="period">
          <el-input v-model="createForm.period" placeholder="YYYY-MM，留空取当前月" />
        </el-form-item>
        <el-form-item label="费用类型" prop="feeType">
          <el-select v-model="createForm.feeType" placeholder="请选择" style="width: 100%">
            <el-option v-for="t in feeTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" prop="amount">
          <el-input v-model="createForm.amount" placeholder="原币金额，>0" type="number" />
        </el-form-item>
        <el-form-item label="币种" prop="currency">
          <el-select v-model="createForm.currency" placeholder="请选择" style="width: 100%">
            <el-option label="CNY" value="CNY" /><el-option label="AED" value="AED" /><el-option label="SAR" value="SAR" /><el-option label="USD" value="USD" />
          </el-select>
        </el-form-item>
        <el-form-item label="汇率" prop="fxRate">
          <el-input v-model="createForm.fxRate" placeholder="CNY 填 1；外币 >0" type="number" />
        </el-form-item>
        <el-form-item label="分摊标记" prop="allocationFlag">
          <el-select v-model="createForm.allocationFlag" style="width: 100%">
            <el-option label="NONE（不摊入成本）" value="NONE" />
            <el-option label="PARTIAL（标记）" value="PARTIAL" />
            <el-option label="FULL（标记）" value="FULL" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createOpen = false">取 消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitCreate">确 定</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="费用详情" v-model="detailOpen" width="560px" append-to-body>
      <el-descriptions :column="2" border size="small" v-if="detail">
        <el-descriptions-item label="费用单号">{{ detail.costNo }}</el-descriptions-item>
        <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="站点">{{ detail.siteCode }}</el-descriptions-item>
        <el-descriptions-item label="ASN">{{ detail.asnId }}</el-descriptions-item>
        <el-descriptions-item label="期间">{{ detail.period }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detail.feeType }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ detail.amount }} {{ detail.currency }}</el-descriptions-item>
        <el-descriptions-item label="汇率">{{ detail.fxRate }}</el-descriptions-item>
        <el-descriptions-item label="分摊标记">{{ detail.allocationFlag }}</el-descriptions-item>
        <el-descriptions-item label="登记时间">{{ detail.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="关联采购单" :span="2">
          <template v-if="detail.poIds && detail.poIds.length">
            <el-tag v-for="pid in detail.poIds" :key="pid" size="small" effect="plain" style="margin-right:6px">{{ poLabelById(pid) }}</el-tag>
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detail.remark }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup name="ShipmentCost">
import { useSiteScope } from "@/composables/useSiteScope"
import { ref, reactive, toRefs, getCurrentInstance } from 'vue'
import { listShipmentCost, getShipmentCost, getShipmentCostResult, createShipmentCost } from '@/api/noon/logistics/shipmentCost'
import { listOrder } from '@/api/noon/purchasing/order'

const { proxy } = getCurrentInstance()
const feeTypes = ['FREIGHT', 'DUTY', 'CUSTOMS', 'PACKAGING', 'LABEL']
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
const total = ref(0)
const createOpen = ref(false)
const detailOpen = ref(false)
const submitting = ref(false)
const detail = ref(null)
const poOptions = ref([])
const poLoading = ref(false)

const data = reactive({
  queryParams: { pageNum: 1, pageSize: 10, siteCode: null, feeType: null, period: null, asnId: null },
  // 登记弹窗默认站点在 openCreate 时取自当前站点；ALL 时留空，必须由用户选择
  createForm: { siteCode: null, asnId: null, poIds: [], period: null, feeType: 'FREIGHT', amount: null, currency: 'CNY', fxRate: 1, allocationFlag: 'NONE', remark: '' }
})
const { queryParams, createForm } = toRefs(data)

function newRequestId(prefix) {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return prefix + crypto.randomUUID().replace(/-/g, '')
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}
function pendingKey() { return 'm4:shipment:pending:create' }
function readPending() { try { return localStorage.getItem(pendingKey()) } catch (e) { return null } }
function writePending(rid) { try { localStorage.setItem(pendingKey(), rid) } catch (e) {} }
function clearPending() { try { localStorage.removeItem(pendingKey()) } catch (e) {} }

const { siteFilter, siteLabel, siteOptions } = useSiteScope(() => {
  queryParams.value.siteCode = siteFilter.value || null
  queryParams.value.pageNum = 1
  getList()
})
queryParams.value.siteCode = siteFilter.value || null
function getList() {
  loading.value = true
  listShipmentCost({
    offset: (queryParams.value.pageNum - 1) * queryParams.value.pageSize,
    limit: queryParams.value.pageSize,
    siteCode: siteFilter.value || undefined,
    feeType: queryParams.value.feeType || undefined,
    period: queryParams.value.period || undefined,
    asnId: queryParams.value.asnId || undefined
  }).then(res => {
    const payload = res.data || {}
    rows.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
  }).catch(() => {}).finally(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { queryParams.value = { pageNum: 1, pageSize: 10, siteCode: null, feeType: null, period: null, asnId: null }; getList() }

// 加载当前站点的采购单供多选（真实 listOrder；ALL 时不预选站点，仍拉取全部授权站点）
function loadPos(siteCode) {
  poLoading.value = true
  listOrder({ offset: 0, limit: 200, siteCode: siteCode || undefined }).then(res => {
    const payload = res.data || {}
    poOptions.value = Array.isArray(payload.rows) ? payload.rows : []
  }).catch(() => { poOptions.value = [] }).finally(() => { poLoading.value = false })
}
function poOrderLabel(po) {
  const status = po.status || ''
  const amount = po.totalAmount != null ? ` ¥${po.totalAmount}` : ''
  return `${po.orderNo}${amount}（${status}）`
}
function poLabelById(id) {
  const po = poOptions.value.find(p => p.id === id)
  return po ? po.orderNo : ('#' + id)
}

function resetCreateForm() {
  Object.assign(createForm.value, { siteCode: siteFilter.value || null, asnId: null, poIds: [], period: null, feeType: 'FREIGHT', amount: null, currency: 'CNY', fxRate: 1, allocationFlag: 'NONE', remark: '' })
}
function handleCreate() {
  resetCreateForm()
  createOpen.value = true
  loadPos(siteFilter.value || null)
}

function doCreate(rid) {
  const f = createForm.value
  const body = {
    siteCode: f.siteCode,
    asnId: f.asnId ? Number(f.asnId) : null,
    poIds: (f.poIds && f.poIds.length) ? f.poIds.map(Number) : [],
    period: f.period || undefined,
    feeType: f.feeType,
    amount: Number(f.amount),
    currency: f.currency,
    fxRate: Number(f.fxRate),
    allocationFlag: f.allocationFlag,
    remark: f.remark || undefined,
    requestId: rid
  }
  createShipmentCost(body).then(res => {
    proxy.$modal.msgSuccess('登记成功：' + (res.data && res.data.costNo ? res.data.costNo : ''))
    clearPending(); createOpen.value = false; getList()
  }).catch(err => {
    proxy.$modal.msgError((err && err.msg) || '登记失败')
  }).finally(() => { submitting.value = false })
}
function submitCreate() {
  const f = createForm.value
  if (!f.siteCode || !f.feeType || !(Number(f.amount) > 0) || !f.currency || !(Number(f.fxRate) > 0)) {
    proxy.$modal.msgWarning('请填写站点/类型/金额>0/币种/汇率>0'); return
  }
  submitting.value = true
  const pending = readPending()
  if (pending) {
    getShipmentCostResult(pending).then(res => {
      if (res.data && res.data.replayed === true) {
        proxy.$modal.msgSuccess('该笔已登记（幂等重开）'); clearPending(); createOpen.value = false; getList(); submitting.value = false
      } else doCreate(pending)
    }).catch(() => doCreate(pending))
  } else {
    const rid = newRequestId('SC'); writePending(rid); doCreate(rid)
  }
}

function handleDetail(row) {
  getShipmentCost(row.id).then(res => {
    detail.value = res.data
    detailOpen.value = true
    if (res.data && res.data.poIds && res.data.poIds.length && !poOptions.value.length)
      loadPos(res.data.siteCode)
  }).catch(() => {})
}

getList()
</script>
