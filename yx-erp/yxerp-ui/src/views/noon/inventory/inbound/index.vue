<template>
  <div class="app-container">
    <el-alert
      title="M4 隔离演示数据：入库确认后生成 FIFO 批次并更新四态台账；成本只含采购价、不摊头程。调拨/ASN/盘点属后续模块。"
      type="warning" :closable="false" show-icon style="margin-bottom: 12px" />
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="86px">
      <el-form-item label="站点" prop="siteCode">
        <el-select v-model="queryParams.siteCode" clearable placeholder="全部授权站点" style="width:180px">
          <el-option v-for="s in siteOptions" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['noon:inventory:inbound:add']" @click="handleAdd">新增入库</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>

    <el-table v-loading="loading" :data="inboundList" border>
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="入库单号" align="center" prop="inboundNo" min-width="180" />
      <el-table-column label="采购单ID" align="center" prop="purOrderId" width="110" />
      <el-table-column label="仓库ID" align="center" prop="warehouseId" width="100" />
      <el-table-column label="站点" align="center" prop="siteCode" width="90" />
      <el-table-column label="明细数" align="center" prop="itemCount" width="90" />
      <el-table-column label="状态" align="center" prop="status" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createdAt" min-width="170" />
      <el-table-column label="操作" align="center" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />

    <!-- 新增入库弹窗 -->
    <el-dialog :title="formTitle" v-model="formOpen" width="720px" append-to-body>
      <el-form :model="form" ref="formRef" label-width="110px">
        <el-form-item label="采购单ID" prop="purOrderId">
          <el-input-number v-model="form.purOrderId" :min="1" :precision="0" controls-position="right" style="width:200px" @change="loadOrderItems" />
          <el-button style="margin-left:8px" @click="loadOrderItems">加载收货明细</el-button>
        </el-form-item>
        <el-form-item label="仓库ID" prop="warehouseId">
          <el-input-number v-model="form.warehouseId" :min="1" :precision="0" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="站点" prop="siteCode">
          <el-select v-model="form.siteCode" clearable placeholder="与仓库归属一致" style="width:180px">
            <el-option v-for="s in siteOptions" :key="s" :label="s" :value="s" />
          </el-select>
          <span style="margin-left:8px;color:#909399;font-size:12px">实际以仓库归属为准校验</span>
        </el-form-item>
      </el-form>
      <el-table :data="form.lines" border size="small" v-loading="itemsLoading">
        <el-table-column label="采购明细ID" prop="purOrderItemId" width="120" align="center" />
        <el-table-column label="SKU" prop="skuId" width="110" align="center" />
        <el-table-column label="已收货" prop="qtyReceived" width="100" align="center" />
        <el-table-column label="合格量" width="150" align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.qtyQualified" :min="1" :max="row.qtyReceived" :precision="0" controls-position="right" style="width:130px" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitInbound">确认入库</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="入库单详情" v-model="detailOpen" width="720px" append-to-body>
      <el-descriptions :column="2" border size="small" v-if="detail.head">
        <el-descriptions-item label="入库单号">{{ detail.head.inboundNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.head.status }}</el-descriptions-item>
        <el-descriptions-item label="采购单ID">{{ detail.head.purOrderId }}</el-descriptions-item>
        <el-descriptions-item label="仓库ID">{{ detail.head.warehouseId }}</el-descriptions-item>
        <el-descriptions-item label="站点">{{ detail.head.siteCode }}</el-descriptions-item>
        <el-descriptions-item label="明细数">{{ detail.head.itemCount }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.items" border size="small" style="margin-top:12px">
        <el-table-column label="采购明细ID" prop="purOrderItemId" align="center" />
        <el-table-column label="SKU" prop="skuId" align="center" />
        <el-table-column label="批次号" prop="batchNo" min-width="180" />
        <el-table-column label="到货量" prop="qty" align="center" />
        <el-table-column label="合格量" prop="qtyQualified" align="center" />
        <el-table-column label="单位成本" prop="unitCost" align="center" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="InboundInventory">
import { listInbound, getInbound, confirmInbound, getInboundResult } from "@/api/noon/inventory/inbound"
import { getOrder } from "@/api/noon/purchasing/order"

const { proxy } = getCurrentInstance()
const inboundList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const siteOptions = ref(["AE", "SA"])
const formOpen = ref(false)
const detailOpen = ref(false)
const submitting = ref(false)
const itemsLoading = ref(false)
const formTitle = ref("新增入库")
const detail = ref({ head: null, items: [] })
const data = reactive({
  queryParams: { pageNum: 1, limit: 10, siteCode: null },
  form: { purOrderId: null, warehouseId: null, siteCode: null, lines: [] }
})
const { queryParams, form } = toRefs(data)

function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listInbound(params).then(res => {
    const payload = res.data || {}
    inboundList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }

function newRequestId() {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID().replace(/-/g, "")
  return "IN" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}
/** pending requestId 存本地：提交成功/确认入账后清除；重开先查 result，避免响应不明重复提交。 */
function pendingKey(orderId) { return "m4:inbound:pending:" + orderId }
function readPending(orderId) { try { return localStorage.getItem(pendingKey(orderId)) } catch (e) { return null } }
function writePending(orderId, rid) { try { localStorage.setItem(pendingKey(orderId), rid) } catch (e) {} }
function clearPending(orderId) { try { localStorage.removeItem(pendingKey(orderId)) } catch (e) {} }

function handleAdd() {
  form.value.purOrderId = null; form.value.warehouseId = null; form.value.siteCode = null; form.value.lines = []
  formTitle.value = "新增入库"
  formOpen.value = true
}
function loadOrderItems() {
  if (!form.value.purOrderId) { proxy.$modal.msgWarning("请先输入采购单ID"); return }
  itemsLoading.value = true
  getOrder(form.value.purOrderId).then(res => {
    const o = res.data || {}
    const items = Array.isArray(o.items) ? o.items : []
    form.value.lines = items.map(it => ({
      purOrderItemId: it.id, skuId: it.skuId,
      qtyReceived: Number(it.qtyReceived || 0),
      qtyQualified: Number(it.qtyReceived || 0) > 0 ? 1 : 0
    }))
    itemsLoading.value = false
    if (form.value.lines.length === 0) proxy.$modal.msgWarning("该采购单无已收货明细，无法入库")
  }).catch(() => { itemsLoading.value = false })
}
function submitInbound() {
  const lines = form.value.lines.filter(l => l.qtyQualified > 0).map(l => ({
    purOrderItemId: l.purOrderItemId, qtyQualified: l.qtyQualified
  }))
  if (!form.value.purOrderId || !form.value.warehouseId || lines.length === 0) {
    proxy.$modal.msgWarning("请填写采购单、仓库并至少保留一行合格量"); return
  }
  const pending = readPending(form.value.purOrderId)
  const applyConfirm = rid => {
    writePending(form.value.purOrderId, rid)
    submitting.value = true
    confirmInbound({ purOrderId: form.value.purOrderId, warehouseId: form.value.warehouseId,
      siteCode: form.value.siteCode, lines, requestId: rid }).then(res => {
      submitting.value = false
      if (res && res.data && res.data.replayed === true)
        proxy.$modal.msgWarning("该入库请求已处理，未重复入账（幂等重放）")
      else
        proxy.$modal.msgSuccess("入库成功，已生成 FIFO 批次")
      clearPending(form.value.purOrderId)
      formOpen.value = false; getList()
    }).catch(() => { submitting.value = false /* 失败保留原 requestId，供重试复用 */ })
  }
  if (pending) {
    // 重开/响应不明：先查上次 requestId 是否已入账，已入账则不再提交
    getInboundResult(pending).then(res => {
      const prior = res && res.data
      if (prior && prior.inboundId) {
        proxy.$modal.msgWarning("上次入库请求已入账，未重复提交")
        clearPending(form.value.purOrderId); formOpen.value = false; getList()
      } else {
        applyConfirm(pending)
      }
    }).catch(() => applyConfirm(pending))
  } else {
    applyConfirm(newRequestId())
  }
}
function handleDetail(row) {
  getInbound(row.id).then(res => {
    detail.value = res.data || { head: null, items: [] }
    detailOpen.value = true
  })
}
getList()
</script>
