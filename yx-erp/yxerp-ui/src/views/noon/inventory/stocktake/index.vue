<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 盘点 DRAFT → APPLIED（应用后不可撤销）；账面量按应用时实时库存重算；物理处置未实现`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <!-- 查询区：沿用若依代码生成器 inv_stocktake 骨架 -->
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="82px">
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
        <el-button type="primary" plain icon="Plus" @click="handleCreate"
          v-hasPermi="['noon:inventory:stocktake:create']">新建盘点</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 列表：生成骨架 prop 结构 -->
    <el-table v-loading="loading" :data="stocktakeList">
      <el-table-column label="ID" align="center" prop="id" width="70" />
      <el-table-column label="盘点单号" align="center" prop="stocktakeNo" min-width="200" />
      <el-table-column label="仓库" align="center" prop="warehouseId" width="90" />
      <el-table-column label="站点" align="center" prop="siteCode" width="80" />
      <el-table-column label="期间" align="center" prop="period" width="100" />
      <el-table-column label="状态" align="center" prop="status" width="110">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'APPLIED' ? 'success' : 'info'">{{ { DRAFT: '草稿', APPLIED: '已应用' }[scope.row.status] || scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createdAt" min-width="170" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="220">
        <template #default="scope">
          <el-button link type="primary" icon="Document" @click="handleDetail(scope.row)">详情</el-button>
          <el-button link type="warning" icon="Check" @click="handleApply(scope.row)"
            v-hasPermi="['noon:inventory:stocktake:apply']" :disabled="scope.row.status !== 'DRAFT'">应用盘点</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum"
      v-model:limit="queryParams.pageSize" @pagination="getList" />

    <!-- 新建盘点弹窗 -->
    <el-dialog title="新建盘点单" v-model="createOpen" width="760px" append-to-body>
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="站点">
          <el-tag type="info" effect="plain">{{ siteLabel }}（由顶栏站点上下文决定，仓库须落在该站点）</el-tag>
        </el-form-item>
        <el-form-item label="仓库 ID" required>
          <el-input-number v-model="createForm.warehouseId" :min="1" :precision="0" placeholder="盘点仓库" />
        </el-form-item>
        <el-form-item label="期间">
          <el-input v-model="createForm.period" placeholder="YYYY-MM（可空，如 2026-11）" style="width: 200px" />
        </el-form-item>
        <el-form-item label="盘点明细">
          <el-button type="primary" plain icon="Plus" size="mini" @click="addLine">新增行</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="createForm.lines" border size="mini">
        <el-table-column label="SKU ID" width="120">
          <template #default="scope">
            <el-input-number v-model="scope.row.skuId" :min="1" :precision="0" size="mini" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="批次号（可空=按SKU）" min-width="180">
          <template #default="scope">
            <el-input v-model="scope.row.batchNo" size="mini" placeholder="留空按 SKU 全仓盘点" />
          </template>
        </el-table-column>
        <el-table-column label="实盘数量" width="140">
          <template #default="scope">
            <el-input-number v-model="scope.row.qtyActual" :min="0" :precision="0" size="mini" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="盘点事件号" min-width="170">
          <template #default="scope">
            <el-input v-model="scope.row.countEventId" size="mini" placeholder="STK 事件号，自动生成" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="scope">
            <el-button link type="danger" size="mini" @click="removeLine(scope.$index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">确定新建</el-button>
      </template>
    </el-dialog>

    <!-- 应用盘点弹窗（展示账面/实盘/差异，确认过账） -->
    <el-dialog title="应用盘点（过账）" v-model="applyOpen" width="680px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon
        title="应用后不可撤销：盘亏按 FIFO 扣减批次与可用库存，盘盈新建调整批次承接成本。请核对差异。" />
      <el-table :data="applyForm.items" border size="mini" style="margin-top: 12px">
        <el-table-column label="SKU" prop="skuId" width="90" />
        <el-table-column label="批次号" prop="batchNo" min-width="170" />
        <el-table-column label="账面量" prop="qtyBook" width="100" />
        <el-table-column label="实盘量" prop="qtyActual" width="100" />
        <el-table-column label="差异" prop="qtyDiff" width="100">
          <template #default="scope">
            <span :style="scope.row.qtyDiff > 0 ? 'color:#67c23a' : (scope.row.qtyDiff < 0 ? 'color:#f56c6c' : '')">
              {{ scope.row.qtyDiff > 0 ? '盘盈 +' : '' }}{{ scope.row.qtyDiff }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="applyOpen = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitApply">确认应用</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="盘点单详情" v-model="detailOpen" width="760px" append-to-body>
      <el-descriptions :column="2" border size="mini">
        <el-descriptions-item label="盘点单号">{{ detail.head && detail.head.stocktakeNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.head && detail.head.status }}</el-descriptions-item>
        <el-descriptions-item label="仓库">{{ detail.head && detail.head.warehouseId }}</el-descriptions-item>
        <el-descriptions-item label="站点">{{ detail.head && detail.head.siteCode }}</el-descriptions-item>
        <el-descriptions-item label="期间">{{ detail.head && detail.head.period }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.items" border size="mini" style="margin-top: 12px">
        <el-table-column label="SKU" prop="skuId" />
        <el-table-column label="批次号" prop="batchNo" />
        <el-table-column label="账面量" prop="qtyBook" />
        <el-table-column label="实盘量" prop="qtyActual" />
        <el-table-column label="差异" prop="qtyDiff" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="StocktakeInventory">
import { useSiteScope } from "@/composables/useSiteScope"
import { listStocktake, getStocktake, createStocktake, applyStocktake, getStocktakeResult } from "@/api/noon/inventory/stocktake"

const { proxy } = getCurrentInstance()
const stocktakeList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const createOpen = ref(false)
const applyOpen = ref(false)
const detailOpen = ref(false)
const submitting = ref(false)
const detail = ref({ head: null, items: [] })
const data = reactive({
  queryParams: { pageNum: 1, pageSize: 10, siteCode: null },
  createForm: { warehouseId: null, period: null, lines: [] },
  applyForm: { stocktakeId: null, requestId: null, items: [] }
})
const { queryParams, createForm, applyForm } = toRefs(data)

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
  listStocktake(params).then(response => {
    const payload = response.data || {}
    stocktakeList.value = Array.isArray(payload.rows) ? payload.rows : []
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
function newLine() { return { skuId: null, batchNo: "", qtyActual: 0, countEventId: newRequestId("CT") } }
function addLine() { createForm.value.lines.push(newLine()) }
function removeLine(i) { createForm.value.lines.splice(i, 1) }

function handleCreate() {
  createForm.value.warehouseId = null
  createForm.value.period = null
  createForm.value.lines = [newLine()]
  createOpen.value = true
}
function submitCreate() {
  const lines = createForm.value.lines.map(l => ({
    skuId: Number(l.skuId), batchNo: (l.batchNo || "").trim(),
    qtyActual: Number(l.qtyActual), countEventId: (l.countEventId || "").trim() || newRequestId("CT")
  }))
  if (!(createForm.value.warehouseId > 0)) { proxy.$modal.msgWarning("请填写盘点仓库"); return }
  if (lines.length === 0 || lines.some(l => !(l.skuId > 0) || !l.countEventId || l.qtyActual < 0)) {
    proxy.$modal.msgWarning("请完整填写每行的 SKU、实盘量与盘点事件号"); return
  }
  submitting.value = true
  createStocktake({ warehouseId: createForm.value.warehouseId, period: createForm.value.period || null,
    lines, requestId: newRequestId("SC") }).then(res => {
    submitting.value = false
    if (res && res.data && res.data.replayed === true) proxy.$modal.msgWarning("该建单请求已处理（幂等重放）")
    else proxy.$modal.msgSuccess("盘点单已创建，账面量在应用时按实时库存计算")
    createOpen.value = false; getList()
  }).catch(e => { submitting.value = false; proxy.$modal.msgError((e && e.msg) || "新建失败，请检查仓库授权") })
}

function handleApply(row) {
  getStocktake(row.id).then(res => {
    const d = res.data || {}
    applyForm.value.stocktakeId = row.id
    applyForm.value.items = Array.isArray(d.items) ? d.items : []
    // 应用阶段单独生成 requestId（动作级幂等，CREATE/APPLY 隔离）
    applyForm.value.requestId = readPending(row.id) || newRequestId("SA")
    writePending(row.id, applyForm.value.requestId)
    applyOpen.value = true
  }).catch(e => proxy.$modal.msgError((e && e.msg) || "加载盘点详情失败"))
}
function pendingKey(id) { return "m4:stocktake:pending:" + id }
function readPending(id) { try { return localStorage.getItem(pendingKey(id)) } catch (e) { return null } }
function writePending(id, rid) { try { localStorage.setItem(pendingKey(id), rid) } catch (e) {} }
function clearPending(id) { try { localStorage.removeItem(pendingKey(id)) } catch (e) {} }

function submitApply() {
  if (!applyForm.value.stocktakeId) { proxy.$modal.msgWarning("无效盘点单"); return }
  const rid = applyForm.value.requestId
  submitting.value = true
  applyStocktake({ stocktakeId: applyForm.value.stocktakeId, requestId: rid }).then(res => {
    submitting.value = false
    if (res && res.data && res.data.replayed === true) proxy.$modal.msgWarning("该盘点请求已处理（幂等重放）")
    else proxy.$modal.msgSuccess("盘点已应用，四态台账已更新")
    clearPending(applyForm.value.stocktakeId)
    applyOpen.value = false; getList()
  }).catch(e => {
    submitting.value = false
    // 响应不明时先查 result，避免重复提交导致重复过账
    getStocktakeResult(rid).then(r => {
      if (r && r.data) { clearPending(applyForm.value.stocktakeId); applyOpen.value = false; getList(); proxy.$modal.msgSuccess("盘点已过账（结果回查）") }
      else proxy.$modal.msgError((e && e.msg) || "应用失败，请检查库存与授权")
    }).catch(() => proxy.$modal.msgError((e && e.msg) || "应用失败，请检查库存与授权"))
  })
}

function handleDetail(row) {
  getStocktake(row.id).then(res => { detail.value = res.data || { head: null, items: [] }; detailOpen.value = true })
}
</script>
