<!--
  M4 调拨单管理（发运在途拆批 / 收货转 OPEN + 短少）
  基于若依代码生成器 inv_transfer 骨架改造：el-form 查询区 + right-toolbar + el-table + pagination、
  <script setup> 的 data/queryParams/getList/handleQuery/resetQuery 结构均沿用生成骨架
  （见 docs/verification/m4-transfer/generated/ADOPTION.md）。跨表 ship/receive 交互为手写。
-->
<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 调拨：发运拆在途子批次，收货转 OPEN；短少须登记异常，未实现项已标注`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-alert title="M4 调拨闭环：发运从源批次拆出在途子批次（源仓可用减/目标仓在途增），收货转 OPEN 并冲销短少。调拨单由上游单据/种子产生，本页负责发运与收货。" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="80px">
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
        <el-button type="primary" plain icon="Plus" @click="handleCreate" v-hasPermi="['noon:inventory:transfer:add']">新建调拨</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="transferList" border>
      <el-table-column label="ID" align="center" prop="id" width="70" />
      <el-table-column label="调拨单号" align="center" prop="transferNo" min-width="180" />
      <el-table-column label="源仓" align="center" prop="fromWarehouseId" width="90" />
      <el-table-column label="目标仓" align="center" prop="toWarehouseId" width="90" />
      <el-table-column label="站点" align="center" prop="siteCode" width="80" />
      <el-table-column label="明细数" align="center" prop="itemCount" width="90" />
      <el-table-column label="状态" align="center" prop="status" width="120">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'RECEIVED' ? 'success' : (scope.row.status === 'IN_TRANSIT' ? 'warning' : 'info')">{{ { DRAFT: '草稿', IN_TRANSIT: '在途', RECEIVED: '已收货' }[scope.row.status] || scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createdAt" min-width="170" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Van" @click="handleShip(scope.row)" v-hasPermi="['noon:inventory:transfer:ship']" :disabled="scope.row.status !== 'DRAFT'">发运</el-button>
          <el-button link type="primary" icon="SuccessFilled" @click="handleReceive(scope.row)" v-hasPermi="['noon:inventory:transfer:receive']" :disabled="scope.row.status !== 'IN_TRANSIT'">收货</el-button>
          <el-button link type="info" icon="View" @click="handleDetail(scope.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.pageNum"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 新建调拨弹窗 -->
    <el-dialog title="新建调拨单" v-model="createOpen" width="720px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="站点">
          <el-tag type="info" effect="plain">{{ siteLabel }}（由顶栏站点上下文决定，源仓/目标仓须落在该站点）</el-tag>
        </el-form-item>
        <el-form-item label="源仓ID"><el-input-number v-model="createForm.fromWarehouseId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="目标仓ID"><el-input-number v-model="createForm.toWarehouseId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="明细行">
          <el-table :data="createForm.lines" size="mini" border>
            <el-table-column label="SKU ID" width="110">
              <template #default="scope"><el-input-number v-model="scope.row.skuId" :min="1" :precision="0" size="mini" controls-position="right" /></template>
            </el-table-column>
            <el-table-column label="来源批次号" min-width="180">
              <template #default="scope"><el-input v-model="scope.row.batchNo" size="mini" placeholder="如 B:SEED-1" /></template>
            </el-table-column>
            <el-table-column label="计划量" width="120">
              <template #default="scope"><el-input-number v-model="scope.row.qty" :min="1" :precision="0" size="mini" controls-position="right" /></template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="scope"><el-button link type="danger" icon="Delete" @click="createForm.lines.splice(scope.$index, 1)" /></template>
            </el-table-column>
          </el-table>
          <el-button size="mini" icon="Plus" style="margin-top:8px" @click="createForm.lines.push({ skuId: null, batchNo: '', qty: 1 })">添加明细行</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="submitting" @click="submitCreate">确认新建</el-button>
        <el-button @click="createOpen = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 发运弹窗 -->
    <el-dialog :title="shipTitle" v-model="shipOpen" width="640px" append-to-body>
      <el-form label-width="110px">
        <el-form-item label="源仓ID"><el-input-number v-model="shipForm.fromWarehouseId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="目标仓ID"><el-input-number v-model="shipForm.toWarehouseId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="发运明细">
          <el-table :data="shipForm.lines" size="mini" border>
            <el-table-column label="明细ID" prop="itemId" width="90" />
            <el-table-column label="SKU" prop="skuId" width="90" />
            <el-table-column label="来源批次" prop="batchNo" min-width="170" />
            <el-table-column label="可发量" prop="qtyShippable" width="90" />
            <el-table-column label="发运量" width="130">
              <template #default="scope"><el-input-number v-model="scope.row.qtyShipped" :min="1" :max="scope.row.qtyShippable" :precision="0" size="mini" controls-position="right" /></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="submitting" @click="submitShip">确认发运</el-button>
        <el-button @click="shipOpen = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 收货弹窗 -->
    <el-dialog :title="receiveTitle" v-model="receiveOpen" width="640px" append-to-body>
      <el-form label-width="110px">
        <el-form-item label="目标仓ID"><el-input-number v-model="receiveForm.toWarehouseId" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="收货明细">
          <el-table :data="receiveForm.lines" size="mini" border>
            <el-table-column label="明细ID" prop="itemId" width="90" />
            <el-table-column label="SKU" prop="skuId" width="90" />
            <el-table-column label="已发量" prop="qtyShipped" width="90" />
            <el-table-column label="实收量" width="130">
              <template #default="scope"><el-input-number v-model="scope.row.qtyReceived" :min="0" :max="scope.row.qtyShipped" :precision="0" size="mini" controls-position="right" /></template>
            </el-table-column>
            <el-table-column label="短少事件号" min-width="150">
              <template #default="scope"><el-input v-model="scope.row.shortEventId" size="mini" placeholder="有短少时必填" /></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="submitting" @click="submitReceive">确认收货</el-button>
        <el-button @click="receiveOpen = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="调拨单详情" v-model="detailOpen" width="720px" append-to-body>
      <el-descriptions :column="2" border size="small" v-if="detail.head">
        <el-descriptions-item label="调拨单号">{{ detail.head.transferNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.head.status }}</el-descriptions-item>
        <el-descriptions-item label="源仓">{{ detail.head.fromWarehouseId }}</el-descriptions-item>
        <el-descriptions-item label="目标仓">{{ detail.head.toWarehouseId }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.items" size="mini" border style="margin-top:10px">
        <el-table-column label="SKU" prop="skuId" />
        <el-table-column label="来源批次" prop="batchNo" />
        <el-table-column label="发运量" prop="qtyShipped" />
        <el-table-column label="实收量" prop="qtyReceived" />
        <el-table-column label="差异" prop="diffQty" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="TransferInventory">
import { useSiteScope } from "@/composables/useSiteScope"
import { listTransfer, getTransfer, createTransfer, shipTransfer, receiveTransfer, getTransferResult } from "@/api/noon/inventory/transfer"

const { proxy } = getCurrentInstance()
const transferList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const createOpen = ref(false)
const shipOpen = ref(false)
const receiveOpen = ref(false)
const detailOpen = ref(false)
const submitting = ref(false)
const shipTitle = ref("调拨发运")
const receiveTitle = ref("调拨收货")
const detail = ref({ head: null, items: [] })
const data = reactive({
  queryParams: { pageNum: 1, pageSize: 10, siteCode: null },
  createForm: { fromWarehouseId: null, toWarehouseId: null, lines: [] },
  shipForm: { transferId: null, fromWarehouseId: null, toWarehouseId: null, lines: [] },
  receiveForm: { transferId: null, toWarehouseId: null, lines: [] }
})
const { queryParams, createForm, shipForm, receiveForm } = toRefs(data)

/** 沿用生成器 getList 结构；PageResult 分页换算 */
const { siteFilter, siteLabel } = useSiteScope(() => {
  if (queryParams.value) queryParams.value.siteCode = siteFilter.value || null
  if (queryParams.value) queryParams.value.pageNum = 1
  getList()
})
if (queryParams.value) queryParams.value.siteCode = siteFilter.value || null
function getList() {
  loading.value = true
  const params = {
    offset: (queryParams.value.pageNum - 1) * queryParams.value.pageSize,
    limit: queryParams.value.pageSize,
    siteCode: siteFilter.value || undefined
  }
  listTransfer(params).then(response => {
    const payload = response.data || {}
    transferList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }

function newRequestId(prefix) {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return prefix + crypto.randomUUID().replace(/-/g, "")
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}
function pendingKey(transferId, action) { return "m4:transfer:pending:" + action + ":" + transferId }
function readPending(transferId, action) { try { return localStorage.getItem(pendingKey(transferId, action)) } catch (e) { return null } }
function writePending(transferId, action, rid) { try { localStorage.setItem(pendingKey(transferId, action), rid) } catch (e) {} }
function clearPending(transferId, action) { try { localStorage.removeItem(pendingKey(transferId, action)) } catch (e) {} }

function handleCreate() {
  createForm.value.fromWarehouseId = null
  createForm.value.toWarehouseId = null
  createForm.value.lines = [{ skuId: null, batchNo: '', qty: 1 }]
  createOpen.value = true
}
function submitCreate() {
  const lines = createForm.value.lines.map(l => ({ skuId: Number(l.skuId), batchNo: (l.batchNo || '').trim(), qty: Number(l.qty) }))
  if (!createForm.value.fromWarehouseId || !createForm.value.toWarehouseId) { proxy.$modal.msgWarning("请填写源仓与目标仓"); return }
  if (lines.length === 0 || lines.some(l => !(l.skuId > 0) || !l.batchNo || !(l.qty > 0))) {
    proxy.$modal.msgWarning("请完整填写每行 SKU、来源批次号和计划量"); return
  }
  submitting.value = true
  createTransfer({ fromWarehouseId: createForm.value.fromWarehouseId, toWarehouseId: createForm.value.toWarehouseId,
    siteCode: siteFilter.value || null, lines, requestId: newRequestId("CR") }).then(res => {
    submitting.value = false
    if (res && res.data && res.data.replayed === true) proxy.$modal.msgWarning("该建单请求已处理（幂等重放）")
    else proxy.$modal.msgSuccess("调拨单已创建，可继续发运")
    createOpen.value = false; getList()
  }).catch(e => { submitting.value = false; proxy.$modal.msgError((e && e.msg) || "新建失败，请检查仓库授权与来源批次") })
}
function handleShip(row) {
  getTransfer(row.id).then(res => {
    const d = res.data || {}
    const items = Array.isArray(d.items) ? d.items : []
    shipForm.value.transferId = row.id
    shipForm.value.fromWarehouseId = row.fromWarehouseId
    shipForm.value.toWarehouseId = row.toWarehouseId
    shipForm.value.lines = items.map(it => ({
      itemId: it.itemId, skuId: it.skuId, batchNo: it.batchNo,
      qtyShippable: Number(it.qtyShipped || 0) - Number(it.qtyReceived || 0),
      qtyShipped: Number(it.qtyShipped || 0) - Number(it.qtyReceived || 0)
    }))
    shipTitle.value = "调拨发运 - " + row.transferNo
    shipOpen.value = true
  })
}
function handleReceive(row) {
  getTransfer(row.id).then(res => {
    const d = res.data || {}
    const items = Array.isArray(d.items) ? d.items : []
    receiveForm.value.transferId = row.id
    receiveForm.value.toWarehouseId = row.toWarehouseId
    receiveForm.value.lines = items.map(it => ({
      itemId: it.itemId, skuId: it.skuId,
      qtyShipped: Number(it.qtyShipped || 0) - Number(it.qtyReceived || 0),
      qtyReceived: Number(it.qtyShipped || 0) - Number(it.qtyReceived || 0),
      shortEventId: ""
    }))
    receiveTitle.value = "调拨收货 - " + row.transferNo
    receiveOpen.value = true
  })
}
function handleDetail(row) {
  getTransfer(row.id).then(res => { detail.value = res.data || { head: null, items: [] }; detailOpen.value = true })
}

function submitShip() {
  // 后端 TransferService 要求整单发运：必须覆盖全部调拨行且每行 qtyShipped>0，不能只提交子集。
  const lines = shipForm.value.lines.map(l => ({ itemId: l.itemId, qtyShipped: Number(l.qtyShipped) }))
  if (!shipForm.value.transferId || lines.length === 0) { proxy.$modal.msgWarning("该调拨单没有可发运明细"); return }
  if (lines.some(l => !(l.qtyShipped > 0))) { proxy.$modal.msgWarning("本轮按整单发运，请为全部调拨行填写发运量（每行须大于 0）"); return }
  const apply = rid => {
    writePending(shipForm.value.transferId, "SHIP", rid)
    submitting.value = true
    shipTransfer({ transferId: shipForm.value.transferId, fromWarehouseId: shipForm.value.fromWarehouseId,
      toWarehouseId: shipForm.value.toWarehouseId, siteCode: null, lines, requestId: rid }).then(res => {
      submitting.value = false
      if (res && res.data && res.data.replayed === true) proxy.$modal.msgWarning("该发运请求已处理，未重复拆批（幂等重放）")
      else proxy.$modal.msgSuccess("发运成功，已生成在途子批次")
      clearPending(shipForm.value.transferId, "SHIP"); shipOpen.value = false; getList()
    }).catch(e => { submitting.value = false; proxy.$modal.msgError((e && e.msg) || "发运失败，请检查仓库授权与库存") })
  }
  const pending = readPending(shipForm.value.transferId, "SHIP")
  if (pending) {
    getTransferResult(pending).then(res => {
      const prior = res && res.data
      if (prior && prior.transferId) { proxy.$modal.msgWarning("上次发运请求已处理，未重复提交"); clearPending(shipForm.value.transferId, "SHIP"); shipOpen.value = false; getList() }
      else apply(pending)
    }).catch(() => apply(pending))
  } else apply(newRequestId("SH"))
}
function submitReceive() {
  const lines = receiveForm.value.lines.filter(l => l.qtyReceived >= 0).map(l => ({
    itemId: l.itemId, qtyReceived: l.qtyReceived, shortEventId: l.shortEventId || undefined
  }))
  if (!receiveForm.value.transferId || lines.length === 0) { proxy.$modal.msgWarning("请填写收货明细"); return }
  const apply = rid => {
    writePending(receiveForm.value.transferId, "RECEIVE", rid)
    submitting.value = true
    receiveTransfer({ transferId: receiveForm.value.transferId, toWarehouseId: receiveForm.value.toWarehouseId,
      lines, requestId: rid }).then(res => {
      submitting.value = false
      if (res && res.data && res.data.replayed === true) proxy.$modal.msgWarning("该收货请求已处理，未重复入账（幂等重放）")
      else proxy.$modal.msgSuccess("收货成功，在途已转可用")
      clearPending(receiveForm.value.transferId, "RECEIVE"); receiveOpen.value = false; getList()
    }).catch(e => { submitting.value = false; proxy.$modal.msgError((e && e.msg) || "收货失败，请检查仓库授权与在途批次") })
  }
  const pending = readPending(receiveForm.value.transferId, "RECEIVE")
  if (pending) {
    getTransferResult(pending).then(res => {
      const prior = res && res.data
      if (prior && prior.transferId) { proxy.$modal.msgWarning("上次收货请求已处理，未重复提交"); clearPending(receiveForm.value.transferId, "RECEIVE"); receiveOpen.value = false; getList() }
      else apply(pending)
    }).catch(() => apply(pending))
  } else apply(newRequestId("RC"))
}
getList()
</script>
