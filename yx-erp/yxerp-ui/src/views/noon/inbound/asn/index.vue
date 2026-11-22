<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · ASN：DRAFT → SEALED → RECEIVED（子批次 SEALED 后为 IN_TRANSIT）；仅 DRAFT 可封单、仅 SEALED 可收货；Slot 预约未实现`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />
  <el-alert title="直发主路径：采购单 → 头程费用（关联采购单）→ ASN 采购直发 → FBN 官方仓实收。直发以采购收货成本为成本源，不经本地仓、不产生自有仓可用库存；FBN 官方仓为系统内部记录，不代表 Noon 平台已建仓/已预约。" type="warning" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryFormRef" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="站点">
        <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" size="mini" v-hasPermi="['noon:inventory:asn:create']" @click="handleCreate">新建 ASN</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="ID" prop="asnId" width="80" />
      <el-table-column label="ASN 号" prop="asnNo" min-width="180" />
      <el-table-column label="站点" prop="siteCode" width="70" />
      <el-table-column label="FBN 仓" prop="warehouseCode" width="130" />
      <el-table-column label="来源" width="100">
        <template #default="scope">
          <el-tag size="small" :type="scope.row.sourceMode === 'DIRECT_PURCHASE' ? 'success' : 'info'" effect="plain">{{ scope.row.sourceMode === 'DIRECT_PURCHASE' ? '采购直发' : '本地仓批次' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" prop="status" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'RECEIVED' ? 'success' : (scope.row.status === 'SEALED' ? 'warning' : 'info')">{{ { DRAFT: '草稿', SEALED: '已封单', RECEIVED: '已收货' }[scope.row.status] || scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="行数" prop="itemCount" width="70" />
      <el-table-column label="创建时间" prop="createdAt" width="160" />
      <el-table-column label="操作" align="center" min-width="200" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-button link type="primary" size="mini" v-hasPermi="['noon:inventory:asn:list']" @click="handleDetail(scope.row)">详情</el-button>
          <el-button link type="warning" size="mini" v-if="scope.row.status === 'DRAFT'" v-hasPermi="['noon:inventory:asn:seal']" @click="handleSeal(scope.row)">封单发运</el-button>
          <el-button link type="success" size="mini" v-if="scope.row.status === 'SEALED'" v-hasPermi="['noon:inventory:asn:receive']" @click="handleReceive(scope.row)">收货登记</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <!-- 新建 ASN 弹窗 -->
    <el-dialog title="新建 ASN" v-model="createOpen" width="640px" append-to-body>
      <el-form ref="createFormRef" :model="createForm" label-width="100px" size="small">
        <el-form-item label="站点" required>
          <el-select v-model="createForm.siteCode" placeholder="请选择授权站点" style="width: 100%">
            <el-option v-for="s in siteOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="FBN 仓编码" prop="warehouseCode">
          <el-input v-model="createForm.warehouseCode" placeholder="如 WH-AE-FBN" />
        </el-form-item>
        <el-form-item label="来源模式">
          <el-radio-group v-model="createForm.sourceMode" @change="onSourceModeChange">
            <el-radio-button value="LOCAL_BATCH">本地仓批次</el-radio-button>
            <el-radio-button value="DIRECT_PURCHASE">采购直发</el-radio-button>
          </el-radio-group>
          <div v-if="createForm.sourceMode === 'DIRECT_PURCHASE'" style="font-size:12px;color:#e6a23c;line-height:1.4">
            直发以采购收货成本为成本源、不经本地仓；采购明细须已收货且未超发。
          </div>
        </el-form-item>
        <el-form-item label="明细行">
          <el-table :data="createForm.lines" size="mini">
            <el-table-column :label="createForm.sourceMode === 'DIRECT_PURCHASE' ? '采购明细（已收货）' : 'SKU ID'" :min-width="createForm.sourceMode === 'DIRECT_PURCHASE' ? 240 : 110">
              <template #default="s">
                <el-select v-if="createForm.sourceMode === 'DIRECT_PURCHASE'" v-model="s.row.purOrderItemId"
                           filterable placeholder="选择采购单明细" style="width:100%" @change="onPoItemChange(s.row)">
                  <el-option v-for="opt in poItemOptions" :key="opt.id" :label="opt.label" :value="opt.id" />
                </el-select>
                <el-input v-else v-model.number="s.row.skuId" type="number" placeholder="skuId" />
              </template>
            </el-table-column>
            <el-table-column v-if="createForm.sourceMode === 'LOCAL_BATCH'" label="源批次号" min-width="150">
              <template #default="s"><el-input v-model="s.row.batchNo" placeholder="本地仓 OPEN 批次" /></template>
            </el-table-column>
            <el-table-column v-if="createForm.sourceMode === 'DIRECT_PURCHASE'" label="SKU" width="90">
              <template #default="s"><span>{{ s.row.skuId || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="发运量" width="110">
              <template #default="s"><el-input v-model.number="s.row.qtyShipped" type="number" placeholder=">0" /></template>
            </el-table-column>
            <el-table-column label="Partner SKU" min-width="130">
              <template #default="s"><el-input v-model="s.row.partnerSku" placeholder="可选" /></template>
            </el-table-column>
            <el-table-column width="60">
              <template #default="s"><el-button link type="danger" @click="createForm.lines.splice(s.$index,1)">删</el-button></template>
            </el-table-column>
          </el-table>
          <el-button size="mini" icon="Plus" @click="addLine" style="margin-top: 6px">加一行</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="createOpen = false">取 消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitCreate">确 定</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 封单弹窗 -->
    <el-dialog title="封单发运" v-model="sealOpen" width="440px" append-to-body>
      <el-alert type="info" :closable="false" title="封单将生成在途子批次。本地仓模式另会扣减源仓可用；采购直发模式无源仓、不扣减，仅增加 FBN 仓在途。请确认货已实际发运。" />
      <div style="margin-top: 12px">ASN：{{ sealTarget && sealTarget.asnNo }}</div>
      <template #footer>
        <el-button @click="sealOpen = false">取 消</el-button>
        <el-button type="warning" :loading="submitting" @click="doSeal">确认封单</el-button>
      </template>
    </el-dialog>

    <!-- 收货弹窗 -->
    <el-dialog title="收货登记" v-model="receiveOpen" width="640px" append-to-body>
      <el-form ref="receiveFormRef" :model="receiveForm" label-width="90px" size="small">
        <el-form-item label="ASN">
          <span>{{ receiveTarget && receiveTarget.asnNo }}</span>
        </el-form-item>
        <el-form-item label="实收明细">
          <el-table :data="receiveForm.lines" size="mini">
            <el-table-column label="行 ID" prop="asnItemId" width="80" />
            <el-table-column label="SKU" prop="skuId" width="80" />
            <el-table-column label="发运量" prop="qtyShipped" width="80" />
            <el-table-column label="实收量" width="110">
              <template #default="s"><el-input v-model.number="s.row.qtyReceived" type="number" placeholder="0..发运量" /></template>
            </el-table-column>
            <el-table-column label="短少事件号" min-width="140">
              <template #default="s"><el-input v-model="s.row.shortEventId" placeholder="短少时必填" :disabled="!(s.row.qtyShipped - s.row.qtyReceived > 0)" /></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiveOpen = false">取 消</el-button>
        <el-button type="success" :loading="submitting" @click="doReceive">确认收货</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="ASN 详情" v-model="detailOpen" width="720px" append-to-body>
      <el-descriptions :column="2" border size="small" v-if="detail && detail.head">
        <el-descriptions-item label="ASN 号">{{ detail.head.asnNo }}</el-descriptions-item>
        <el-descriptions-item label="ID">{{ detail.head.asnId }}</el-descriptions-item>
        <el-descriptions-item label="站点">{{ detail.head.siteCode }}</el-descriptions-item>
        <el-descriptions-item label="FBN 仓">{{ detail.head.warehouseCode }}</el-descriptions-item>
        <el-descriptions-item label="来源模式">{{ detail.head.sourceMode === 'DIRECT_PURCHASE' ? '采购直发' : '本地仓批次' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.head.status }}</el-descriptions-item>
        <el-descriptions-item label="封单时间">{{ detail.head.sealedAt }}</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 12px" v-if="detail && detail.items && detail.items.length">明细 / 批次血缘：</div>
      <el-table :data="(detail && detail.items) || []" size="mini" v-if="detail && detail.items && detail.items.length" style="margin-top: 6px">
        <el-table-column label="行 ID" prop="id" width="80" />
        <el-table-column label="SKU" prop="skuId" width="80" />
        <el-table-column label="源批次" prop="batchNo" min-width="150" />
        <el-table-column label="发运量" prop="qtyShipped" width="80" />
        <el-table-column label="实收量" prop="qtyReceived" width="80" />
        <el-table-column label="差异" prop="diffQty" width="70" />
        <el-table-column label="子批次" prop="childBatchNo" min-width="150" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="Asn">
import { useSiteScope } from "@/composables/useSiteScope"
import { ref, reactive, toRefs, getCurrentInstance } from 'vue'
import { listAsn, getAsn, getAsnResult, createAsn, sealAsn, receiveAsn } from '@/api/noon/inbound/asn'
import { listOrder, getOrder } from '@/api/noon/purchasing/order'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
const total = ref(0)
const createOpen = ref(false)
const sealOpen = ref(false)
const receiveOpen = ref(false)
const detailOpen = ref(false)
const submitting = ref(false)
const detail = ref(null)
const sealTarget = ref(null)
const receiveTarget = ref(null)
const poItemOptions = ref([])
const poLoading = ref(false)

const data = reactive({
  queryParams: { pageNum: 1, pageSize: 10, siteCode: null },
  // 新建 ASN 默认站点在 openCreate 时取自当前站点；ALL 时留空，必须由用户选择
  createForm: { siteCode: null, warehouseCode: '', sourceMode: 'LOCAL_BATCH', lines: [] },
  receiveForm: { asnId: null, lines: [] }
})
const { queryParams, createForm, receiveForm } = toRefs(data)

function newRequestId(prefix) {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return prefix + crypto.randomUUID().replace(/-/g, '')
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}
function pendingKey(action) { return 'm4:asn:pending:' + action }
function readPending(action) { try { return localStorage.getItem(pendingKey(action)) } catch (e) { return null } }
function writePending(action, rid) { try { localStorage.setItem(pendingKey(action), rid) } catch (e) {} }
function clearPending(action) { try { localStorage.removeItem(pendingKey(action)) } catch (e) {} }

const { siteFilter, siteLabel, siteOptions } = useSiteScope(() => {
  queryParams.value.siteCode = siteFilter.value || null
  queryParams.value.pageNum = 1
  getList()
})
queryParams.value.siteCode = siteFilter.value || null

function getList() {
  loading.value = true
  listAsn({
    offset: (queryParams.value.pageNum - 1) * queryParams.value.pageSize,
    limit: queryParams.value.pageSize,
    siteCode: siteFilter.value || undefined
  }).then(res => {
    const payload = res.data || {}
    rows.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
  }).catch(() => {}).finally(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { queryParams.value = { pageNum: 1, pageSize: 10, siteCode: null }; getList() }

function newLine() {
  return createForm.value.sourceMode === 'DIRECT_PURCHASE'
    ? { skuId: null, batchNo: '', purOrderItemId: null, qtyShipped: null, partnerSku: '' }
    : { skuId: null, batchNo: '', purOrderItemId: null, qtyShipped: null, partnerSku: '' }
}
function addLine() { createForm.value.lines.push(newLine()) }
function onSourceModeChange() { createForm.value.lines = [newLine()]; if (createForm.value.sourceMode === 'DIRECT_PURCHASE') loadPoItems() }
function onPoItemChange(row) {
  const opt = poItemOptions.value.find(o => o.id === row.purOrderItemId)
  if (opt) { row.skuId = opt.skuId; row.qtyReceived = opt.qtyReceived; row.qtyShipped = Math.min(row.qtyShipped || opt.avail, opt.avail) || opt.avail }
}

// 拉取当前站点的采购单明细，筛选已收货（qtyReceived>0）且仍有可发额度的行，供直发选择
function loadPoItems() {
  poLoading.value = true
  listOrder({ offset: 0, limit: 200, siteCode: createForm.value.siteCode || siteFilter.value || undefined }).then(res => {
    const orders = (res.data && res.data.rows) || []
    const details = orders.filter(o => o.status !== 'DRAFT').map(o => getOrder(o.id).catch(() => null))
    return Promise.all(details).then(detailRes => {
      const opts = []
      for (const dr of detailRes) {
        const d = dr && dr.data
        if (!d) continue
        for (const it of (d.items || [])) {
          const received = Number(it.qtyReceived || 0)
          if (received <= 0) continue
          // 可发额度前端按已收量展示；后端会按「已收 − 已建直发ASN发运量」做权威超发校验
          const avail = received
          opts.push({
            id: it.id, skuId: it.skuId,
            label: `${d.orderNo || ('PO#' + d.id)} · SKU${it.skuId} · 已收${received} · 可发≤${avail}`,
            qtyReceived: received, avail: avail
          })
        }
      }
      poItemOptions.value = opts
    })
  }).catch(() => { poItemOptions.value = [] }).finally(() => { poLoading.value = false })
}
function handleCreate() {
  Object.assign(createForm.value, { siteCode: siteFilter.value || null, warehouseCode: '', sourceMode: 'LOCAL_BATCH' })
  createForm.value.lines = [newLine()]
  poItemOptions.value = []
  createOpen.value = true
}
function submitCreate() {
  const f = createForm.value
  if (!f.siteCode || !f.warehouseCode || !f.lines.length) { proxy.$modal.msgWarning('请填站点/FBN 仓/至少一行明细'); return }
  const direct = f.sourceMode === 'DIRECT_PURCHASE'
  for (const l of f.lines) {
    if (!(Number(l.qtyShipped) > 0)) { proxy.$modal.msgWarning('发运量需 >0'); return }
    if (direct) {
      if (!(Number(l.purOrderItemId) > 0)) { proxy.$modal.msgWarning('直发模式需选择已收货的采购明细'); return }
      if (Number(l.qtyShipped) > Number(l.avail || 0)) { proxy.$modal.msgWarning(`发运量不能超过可发额度 ${l.avail || 0}`); return }
    } else if (!(Number(l.skuId) > 0) || !l.batchNo) { proxy.$modal.msgWarning('本地仓模式需填 SKU/源批次'); return }
  }
  submitting.value = true
  const rid = newRequestId('ASN-C')
  writePending('create', rid)
  const body = {
    siteCode: f.siteCode, warehouseCode: f.warehouseCode, sourceMode: f.sourceMode, requestId: rid,
    lines: f.lines.map(l => direct
      ? ({ purOrderItemId: Number(l.purOrderItemId), qtyShipped: Number(l.qtyShipped), partnerSku: l.partnerSku || undefined })
      : ({ skuId: Number(l.skuId), batchNo: l.batchNo, qtyShipped: Number(l.qtyShipped), partnerSku: l.partnerSku || undefined }))
  }
  createAsn(body).then(res => {
    proxy.$modal.msgSuccess('新建成功：' + ((res.data && res.data.asnNo) || ''))
    clearPending('create'); createOpen.value = false; getList()
  }).catch(err => proxy.$modal.msgError((err && err.msg) || '新建失败'))
    .finally(() => { submitting.value = false })
}

function handleSeal(row) { sealTarget.value = row; sealOpen.value = true }
function doSeal() {
  submitting.value = true
  const rid = newRequestId('ASN-S')
  sealAsn({ asnId: sealTarget.value.asnId, requestId: rid }).then(res => {
    proxy.$modal.msgSuccess('封单成功')
    sealOpen.value = false; getList()
  }).catch(err => proxy.$modal.msgError((err && err.msg) || '封单失败'))
    .finally(() => { submitting.value = false })
}

function handleReceive(row) {
  receiveTarget.value = row
  getAsn(row.asnId).then(res => {
    const d = res.data || {}
    receiveForm.value.asnId = row.asnId
    receiveForm.value.lines = (d.items || []).map(it => ({
      asnItemId: it.id, skuId: it.skuId, qtyShipped: it.qtyShipped,
      qtyReceived: it.qtyShipped, shortEventId: ''
    }))
    receiveOpen.value = true
  }).catch(() => {})
}
function doReceive() {
  const f = receiveForm.value
  for (const l of f.lines) {
    if (!(Number(l.qtyReceived) >= 0) || Number(l.qtyReceived) > l.qtyShipped) { proxy.$modal.msgWarning('实收量需在 0..发运量之间'); return }
    if (l.qtyShipped - l.qtyReceived > 0 && !l.shortEventId) { proxy.$modal.msgWarning('短少行需填短少事件号'); return }
  }
  submitting.value = true
  const rid = newRequestId('ASN-R')
  const body = {
    asnId: f.asnId, requestId: rid,
    lines: f.lines.map(l => ({ asnItemId: l.asnItemId, qtyReceived: Number(l.qtyReceived), shortEventId: l.shortEventId || undefined }))
  }
  receiveAsn(body).then(res => {
    proxy.$modal.msgSuccess('收货成功')
    receiveOpen.value = false; getList()
  }).catch(err => proxy.$modal.msgError((err && err.msg) || '收货失败'))
    .finally(() => { submitting.value = false })
}

function handleDetail(row) {
  getAsn(row.asnId).then(res => { detail.value = res.data; detailOpen.value = true }).catch(() => {})
}

getList()
</script>
