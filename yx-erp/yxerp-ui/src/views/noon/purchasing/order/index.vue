<template>
  <div class="app-container">
    <el-alert
      title="M3 隔离演示数据，成本口径只含采购价不含头程费用；收货不生成库存批次。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="86px">
      <el-form-item label="采购单号" prop="orderNo">
        <el-input v-model="queryParams.orderNo" placeholder="请输入采购单号" clearable style="width:180px" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="供应商ID" prop="supplierId">
        <el-input-number v-model="queryParams.supplierId" :min="1" :precision="0" controls-position="right" style="width:150px" placeholder="供应商ID" />
      </el-form-item>
      <el-form-item label="站点" prop="siteCode">
        <el-select v-model="queryParams.siteCode" placeholder="请选择站点" clearable style="width:130px">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:160px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="部分收货" value="PARTIAL_RECEIVED" />
          <el-option label="已收货" value="RECEIVED" />
          <el-option label="已关单" value="CLOSED" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源" prop="source">
        <el-select v-model="queryParams.source" placeholder="请选择来源" clearable style="width:170px">
          <el-option label="手工建单" value="MANUAL" />
          <el-option label="补货建议" value="REPLENISH_SUGGESTION" />
          <el-option label="AI建议" value="AI_SUGGESTION" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:purchasing:order:add']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:purchasing:order:edit']">修改</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="orderList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="采购单号" align="center" prop="orderNo" show-overflow-tooltip />
      <el-table-column label="供应商ID" align="center" prop="supplierId" width="100" />
      <el-table-column label="站点" align="center" prop="siteCode" width="80" />
      <el-table-column label="币种" align="center" prop="currency" width="80" />
      <el-table-column label="采购成本(CNY)" align="center" prop="totalAmount" width="130" />
      <el-table-column label="状态" align="center" prop="status" width="110">
        <template #default="scope">
          <el-tag :type="statusInfo(scope.row.status).type">{{ statusInfo(scope.row.status).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" align="center" prop="source" width="130">
        <template #default="scope">{{ sourceLabel(scope.row.source) }}</template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="250">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-if="scope.row.status === 'DRAFT'" v-hasPermi="['noon:purchasing:order:edit']">修改</el-button>
          <el-button link type="success" icon="Check" @click="handleConfirm(scope.row)" v-if="scope.row.status === 'DRAFT'" v-hasPermi="['noon:purchasing:order:confirm']">确认</el-button>
          <el-button link type="warning" icon="Box" @click="handleReceive(scope.row)" v-if="scope.row.status === 'CONFIRMED' || scope.row.status === 'PARTIAL_RECEIVED'" v-hasPermi="['noon:purchasing:order:receive']">收货</el-button>
          <el-button link type="info" icon="Lock" @click="handleClose(scope.row)" v-if="scope.row.status === 'RECEIVED'" v-hasPermi="['noon:purchasing:order:close']">关单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />

    <el-dialog :title="title" v-model="open" width="960px" append-to-body>
      <el-form ref="orderRef" :model="form" :rules="rules" label-width="100px">
        <el-row>
          <el-col :span="12">
            <el-form-item label="采购单号" prop="orderNo">
              <el-input v-model="form.orderNo" placeholder="请输入采购单号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="供应商ID" prop="supplierId">
              <el-input-number v-model="form.supplierId" :min="1" :precision="0" controls-position="right" style="width:100%" placeholder="请输入供应商ID" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="8">
            <el-form-item label="站点" prop="siteCode">
              <el-select v-model="form.siteCode" placeholder="请选择站点" style="width:100%">
                <el-option label="AE" value="AE" />
                <el-option label="SA" value="SA" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="订单币种" prop="currency">
              <el-select v-model="form.currency" filterable allow-create default-first-option placeholder="币种" style="width:100%" @change="onCurrencyChange">
                <el-option label="CNY" value="CNY" />
                <el-option label="AED" value="AED" />
                <el-option label="SAR" value="SAR" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="来源" prop="source">
              <el-select v-model="form.source" placeholder="请选择来源" style="width:100%">
                <el-option label="手工建单" value="MANUAL" />
                <el-option label="补货建议" value="REPLENISH_SUGGESTION" />
                <el-option label="AI建议" value="AI_SUGGESTION" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源单号" prop="sourceRefNo">
          <el-input v-model="form.sourceRefNo" placeholder="转单来源单号，可留空" />
        </el-form-item>
        <el-form-item label="采购明细" prop="items">
          <el-table :data="form.items" border size="small">
            <el-table-column label="序号" type="index" width="55" align="center" />
            <el-table-column label="SKU ID" width="150" align="center">
              <template #default="scope">
                <el-input-number v-model="scope.row.skuId" :min="1" :precision="0" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="订购数量" width="140" align="center">
              <template #default="scope">
                <el-input-number v-model="scope.row.qtyOrdered" :min="1" :precision="0" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="150" align="center">
              <template #default="scope">
                <el-input-number v-model="scope.row.unitPrice" :min="0.0001" :precision="4" :step="0.01" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="币种" width="110" align="center">
              <template #default="scope">
                <el-select v-model="scope.row.currency" filterable allow-create default-first-option placeholder="币种" style="width:100%">
                  <el-option label="CNY" value="CNY" />
                  <el-option label="AED" value="AED" />
                  <el-option label="SAR" value="SAR" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="汇率" width="110" align="center">
              <template #default="scope">
                <el-input-number v-model="scope.row.fxRate" :min="0" :precision="4" :step="0.01" controls-position="right" style="width:100%" :disabled="scope.row.currency === 'CNY'" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" align="center">
              <template #default="scope">
                <el-button link type="success" @click="fillPrice(scope.$index)">带价</el-button>
                <el-button link type="danger" icon="Delete" @click="removeItem(scope.$index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="primary" plain icon="Plus" size="mini" style="margin-top:8px" @click="addItem">添加明细</el-button>
          <div style="color:#909399;font-size:12px;margin-top:6px">成本口径 = Σ(单价 × 汇率)，CNY 汇率固定为 1；"带价"按当天有效报价带出单价。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog :title="receiveTitle" v-model="receiveOpen" width="760px" append-to-body>
      <el-table :data="receiveForm.lines" border size="small">
        <el-table-column label="明细ID" prop="itemId" width="90" align="center" />
        <el-table-column label="SKU ID" prop="skuId" width="120" align="center" />
        <el-table-column label="订购数量" prop="qtyOrdered" width="100" align="center" />
        <el-table-column label="已收数量" prop="qtyReceived" width="100" align="center" />
        <el-table-column label="可收余量" prop="remain" width="100" align="center" />
        <el-table-column label="本次收货" width="170" align="center">
          <template #default="scope">
            <el-input-number v-model="scope.row.qty" :min="0" :max="scope.row.remain" :precision="0" controls-position="right" style="width:100%" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitReceive">确 定</el-button>
          <el-button @click="receiveOpen = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="OrderPurchasing">
import { listOrder, getOrder, addOrder, updateOrder, confirmOrder, closeOrder } from "@/api/noon/purchasing/order"
import { getEffectiveQuotation } from "@/api/noon/purchasing/quotation"
import { receiveGoods, getReceiptResult } from "@/api/noon/purchasing/receipt"
const { proxy } = getCurrentInstance()
const orderList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const total = ref(0)
const title = ref("")
const receiveOpen = ref(false)
const receiveTitle = ref("")
const receiveForm = reactive({ orderId: null, lines: [], requestId: null })

// 一次收货提交动作生成唯一幂等键：重试复用同一个，真实第二次收货用新值（契约 v2 §9.1）
function newRequestId() {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID().replace(/-/g, "")
  return "R" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

// 未确认收货请求本地暂存（契约 v2.1 §9.6）：提交后/响应不明时保留，成功或确认已入账后清除
function pendingKey(orderId) { return "m3:receipt:pending:" + orderId }
function getPending(orderId) {
  try { return localStorage.getItem(pendingKey(orderId)) } catch (e) { return null }
}
function setPending(orderId, rid) {
  try { localStorage.setItem(pendingKey(orderId), rid) } catch (e) { /* 隐私模式下忽略 */ }
}
function clearPending(orderId) {
  try { localStorage.removeItem(pendingKey(orderId)) } catch (e) { /* 忽略 */ }
}
const statusMap = {
  'DRAFT': { label: '草稿', type: 'info' },
  'CONFIRMED': { label: '已确认', type: 'primary' },
  'PARTIAL_RECEIVED': { label: '部分收货', type: 'warning' },
  'RECEIVED': { label: '已收货', type: 'success' },
  'CLOSED': { label: '已关单', type: 'info' }
}
const sourceMap = {
  'MANUAL': '手工建单',
  'REPLENISH_SUGGESTION': '补货建议',
  'AI_SUGGESTION': 'AI建议'
}
function statusInfo(s) { return statusMap[s] || { label: s || '-', type: 'info' } }
function sourceLabel(s) { return sourceMap[s] || s || '-' }
function newItem() { return { skuId: null, qtyOrdered: 1, unitPrice: null, currency: 'CNY', fxRate: 1 } }
function todayStr() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return d.getFullYear() + '-' + m + '-' + day
}
const data = reactive({
  form: {},
  queryParams: { pageNum: 1, limit: 10,
    orderNo: null,
    supplierId: null,
    siteCode: null,
    status: null,
    source: null,
  },
  rules: {
    orderNo: [{ required: true, message: '采购单号不能为空', trigger: 'blur' }],
    supplierId: [{ required: true, message: '供应商ID不能为空', trigger: 'change' }],
    siteCode: [{ required: true, message: '站点不能为空', trigger: 'change' }],
    currency: [{ required: true, message: '币种不能为空', trigger: 'change' }],
    source: [{ required: true, message: '来源不能为空', trigger: 'change' }],
    items: [{ validator: checkItems, trigger: 'change' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function checkItems(rule, value, callback) {
  if (!value || !value.length) {
    callback(new Error('请至少添加一行采购明细'))
  } else {
    callback()
  }
}
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listOrder(params).then(res => {
    const payload = res.data || {}
    orderList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    id: null,
    orderNo: null,
    supplierId: null,
    siteCode: null,
    currency: 'CNY',
    source: 'MANUAL',
    sourceRefNo: null,
    items: [newItem()],
  }
  proxy.resetForm("orderRef")
}
function addItem() { form.value.items.push(newItem()) }
function removeItem(index) { form.value.items.splice(index, 1) }
function onCurrencyChange(val) {
  form.value.items.forEach(it => {
    it.currency = val
    if (val === 'CNY') it.fxRate = 1
  })
}
function fillPrice(index) {
  const item = form.value.items[index]
  if (!item.skuId || !form.value.supplierId) {
    proxy.$modal.msgWarning("请先填写 SKU ID 与供应商ID")
    return
  }
  getEffectiveQuotation({ skuId: item.skuId, supplierId: form.value.supplierId, at: todayStr() }).then(res => {
    const q = res && res.data
    if (!q) {
      proxy.$modal.msgWarning("未找到该 SKU 与供应商组合的当天有效报价，请手工填写单价")
      return
    }
    if (q.unitPrice != null) item.unitPrice = Number(q.unitPrice)
    if (q.currency) item.currency = q.currency
    if (q.currency === 'CNY') item.fxRate = 1
  })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加采购单" }
function handleUpdate(row) {
  const selected = row || orderList.value.find(i => i.id === ids.value[0])
  if (selected && selected.status && selected.status !== 'DRAFT') {
    proxy.$modal.msgWarning("仅草稿状态采购单可修改")
    return
  }
  reset()
  const _id = selected && selected.id
  if (!_id) return
  getOrder(_id).then(res => {
    const o = res.data || {}
    form.value = {
      id: o.id,
      orderNo: o.orderNo,
      supplierId: o.supplierId,
      siteCode: o.siteCode,
      currency: o.currency || 'CNY',
      source: o.source || 'MANUAL',
      sourceRefNo: o.sourceRefNo,
      items: Array.isArray(o.items) && o.items.length
        ? o.items.map(it => ({
            skuId: it.skuId,
            qtyOrdered: it.qtyOrdered,
            unitPrice: it.unitPrice != null ? Number(it.unitPrice) : null,
            currency: it.currency || o.currency || 'CNY',
            fxRate: it.fxRate != null ? Number(it.fxRate) : 1
          }))
        : [newItem()]
    }
    open.value = true
    title.value = "修改采购单"
  })
}
function submitForm() {
  proxy.$refs["orderRef"].validate(valid => {
    if (!valid) return
    const payload = {
      orderNo: form.value.orderNo,
      supplierId: form.value.supplierId,
      siteCode: form.value.siteCode,
      currency: form.value.currency,
      source: form.value.source,
      sourceRefNo: form.value.sourceRefNo,
      items: form.value.items.map(it => ({
        skuId: it.skuId,
        qtyOrdered: it.qtyOrdered,
        unitPrice: it.unitPrice,
        currency: it.currency || form.value.currency,
        fxRate: it.fxRate
      }))
    }
    const op = form.value.id != null ? updateOrder(form.value.id, payload) : addOrder(payload)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleConfirm(row) {
  confirmOrder(row.id).then(() => { proxy.$modal.msgSuccess("已确认"); getList() })
}
function handleClose(row) {
  closeOrder(row.id).then(() => { proxy.$modal.msgSuccess("已关单"); getList() })
}
function fillReceiveForm(row, o) {
  const items = Array.isArray(o.items) ? o.items : []
  receiveForm.orderId = row.id
  receiveForm.lines = items.map(it => {
    const remain = Number(it.qtyOrdered || 0) - Number(it.qtyReceived || 0)
    return { itemId: it.id, skuId: it.skuId, qtyOrdered: it.qtyOrdered, qtyReceived: it.qtyReceived, remain: remain, qty: remain > 0 ? remain : 0 }
  })
  receiveTitle.value = "采购收货 - " + (o.orderNo || row.orderNo)
}
function handleReceive(row) {
  getOrder(row.id).then(res => {
    const o = res.data || {}
    const pending = getPending(row.id)
    if (!pending) {
      // 无未确认请求：生成新键并暂存，打开弹窗
      receiveForm.requestId = newRequestId()
      setPending(row.id, receiveForm.requestId)
      fillReceiveForm(row, o)
      receiveOpen.value = true
      return
    }
    // 有未确认请求：先查结果，避免响应不明时重复入账（契约 v2.1）
    getReceiptResult(pending).then(rr => {
      const result = rr && rr.data
      if (result) {
        // 已入账：展示结果与提示，清掉 pending；用户如需再收须关闭重开生成新键
        clearPending(row.id)
        proxy.$modal.msgWarning("上次收货请求已成功入账（未重复入账）；如需继续收货请关闭本单后重新打开")
        getList()
        return
      }
      // requestId 不存在/已回滚：复用原键安全，继续打开弹窗
      receiveForm.requestId = pending
      fillReceiveForm(row, o)
      receiveOpen.value = true
    }).catch(() => {
      // 查询被拒（如无权限）：提示且不提交
      proxy.$modal.msgWarning("上次收货请求查询失败或无权限，请刷新后重试")
    })
  })
}
function submitReceive() {
  const lines = receiveForm.lines
    .filter(l => l.qty != null && l.qty > 0)
    .map(l => ({ itemId: l.itemId, qty: Number(l.qty) }))
  if (!lines.length) {
    proxy.$modal.msgWarning("请至少填写一行的本次收货数量")
    return
  }
  // 提交前确保 pending 已记录（响应丢失时重开可查询，不重复入账）
  setPending(receiveForm.orderId, receiveForm.requestId)
  receiveGoods({ orderId: receiveForm.orderId, lines, requestId: receiveForm.requestId }).then(res => {
    // 幂等重放：同一 requestId 已入账过，后端返回首次结果并标记 replayed，不重复计数
    if (res && res.data && res.data.replayed === true)
      proxy.$modal.msgWarning("该收货请求已处理，未重复入账（幂等重放）")
    else
      proxy.$modal.msgSuccess("收货成功")
    clearPending(receiveForm.orderId) // 明确成功，清除未确认标记
    receiveOpen.value = false; getList()
  })
  // 提交失败/响应未知时保留 pending，重开弹窗会先查询再决定是否复用
}
getList()
</script>
