<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 订单：IMPORTED → DELIVERED → RETURNED/CANCELLED；仅 DELIVERED 触发 FIFO；退货 A/B/C 分级处置已接入，时效校验未实现`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-tabs v-model="activeTab" @tab-click="handleTab">
      <!-- ============ 订单 tab ============ -->
      <el-tab-pane label="销售订单" name="order">
        <el-form :model="orderQuery" ref="orderQueryRef" size="small" :inline="true" v-show="showSearch">
          <el-form-item label="站点">
            <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
          </el-form-item>
          <el-form-item label="订单号" prop="orderNo"><el-input v-model="orderQuery.orderNo" placeholder="订单号" clearable style="width: 140px" /></el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="orderQuery.status" placeholder="全部" clearable style="width: 130px">
              <el-option v-for="s in orderStatuses" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="getOrders">搜索</el-button><el-button icon="Refresh" @click="resetOrderQuery">重置</el-button></el-form-item>
        </el-form>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5"><el-button type="primary" plain icon="Plus" size="mini" v-hasPermi="['noon:sales:order:import']" @click="openImportOrder">订单导入</el-button></el-col>
        </el-row>
        <el-table v-loading="loading" :data="orderRows" size="small">
          <el-table-column label="ID" prop="id" width="70" />
          <el-table-column label="订单号" prop="orderNo" min-width="140" />
          <el-table-column label="站点" prop="siteCode" width="60" />
          <el-table-column label="SKU" prop="skuId" width="80" />
          <el-table-column label="数量" prop="qty" width="70" />
          <el-table-column label="已妥投" prop="qtyDelivered" width="80" />
          <el-table-column label="已退货" prop="qtyReturned" width="80" />
          <el-table-column label="金额" prop="grossAmount" width="100" />
          <el-table-column label="状态" prop="status" width="100" />
          <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
            <template #default="scope">
              <el-button link type="primary" size="mini" v-hasPermi="['noon:sales:order:deliver']" @click="openDeliver(scope.row)">妥投</el-button>
              <el-button link type="primary" size="mini" v-hasPermi="['noon:sales:order:list']" @click="showOrderDetail(scope.row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="orderTotal > 0" :total="orderTotal" v-model:page="orderQuery.pageNum" v-model:limit="orderQuery.pageSize" @pagination="getOrders" />
      </el-tab-pane>

      <!-- ============ 退货 tab ============ -->
      <el-tab-pane label="退货单" name="return">
        <el-form :model="returnQuery" ref="returnQueryRef" size="small" :inline="true" v-show="showSearch">
          <el-form-item label="站点">
            <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
          </el-form-item>
          <el-form-item label="订单号" prop="orderNo"><el-input v-model="returnQuery.orderNo" placeholder="订单号" clearable style="width: 140px" /></el-form-item>
          <el-form-item label="分级" prop="grade">
            <el-select v-model="returnQuery.grade" placeholder="全部" clearable style="width: 110px">
              <el-option v-for="g in grades" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="getReturns">搜索</el-button><el-button icon="Refresh" @click="resetReturnQuery">重置</el-button></el-form-item>
        </el-form>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5"><el-button type="primary" plain icon="Plus" size="mini" v-hasPermi="['noon:sales:return:import']" @click="openImportReturn">退货导入</el-button></el-col>
        </el-row>
        <el-table v-loading="loading" :data="returnRows" size="small">
          <el-table-column label="ID" prop="id" width="70" />
          <el-table-column label="退货单号" prop="returnNo" min-width="140" />
          <el-table-column label="订单号" prop="orderNo" min-width="120" />
          <el-table-column label="站点" prop="siteCode" width="60" />
          <el-table-column label="数量" prop="qty" width="70" />
          <el-table-column label="分级" prop="grade" width="70" />
          <el-table-column label="赔偿" prop="compensationAmount" width="90" />
          <el-table-column label="净损" prop="returnLossAmount" width="90" />
          <el-table-column label="退款" prop="refundAmount" width="90" />
          <el-table-column label="状态" prop="status" width="90" />
          <el-table-column label="操作" align="center" width="180" class-name="small-padding fixed-width">
            <template #default="scope">
              <el-button link type="primary" size="mini" v-hasPermi="['noon:sales:return:reverse']" @click="openReverse(scope.row)">红冲</el-button>
              <el-button link type="primary" size="mini" v-hasPermi="['noon:sales:dispose:edit']" @click="openDispose(scope.row)">处置</el-button>
              <el-button link type="primary" size="mini" v-hasPermi="['noon:sales:return:list']" @click="showReturnDetail(scope.row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="returnTotal > 0" :total="returnTotal" v-model:page="returnQuery.pageNum" v-model:limit="returnQuery.pageSize" @pagination="getReturns" />
      </el-tab-pane>
    </el-tabs>

    <!-- 订单导入弹窗 -->
    <el-dialog title="订单导入" v-model="importOrderOpen" width="560px" append-to-body>
      <el-form ref="importOrderFormRef" :model="importOrderForm" label-width="100px" size="small">
        <el-form-item label="站点" required><el-select v-model="importOrderForm.siteCode" placeholder="请选择授权站点" style="width:100%"><el-option v-for="s in siteOptions" :key="s.value" :label="s.label" :value="s.value" /></el-select></el-form-item>
        <el-form-item label="订单号"><el-input v-model="importOrderForm.orderNo" placeholder="平台订单号" /></el-form-item>
        <el-form-item label="门店ID"><el-input v-model="importOrderForm.storeId" type="number" placeholder="md_store id" /></el-form-item>
        <el-form-item label="SKU ID"><el-input v-model="importOrderForm.skuId" type="number" placeholder="md_sku id" /></el-form-item>
        <el-form-item label="Partner SKU"><el-input v-model="importOrderForm.partnerSku" placeholder="md_partner_sku 映射" /></el-form-item>
        <el-form-item label="金额"><el-input v-model="importOrderForm.grossAmount" type="number" placeholder="原币金额，可为0" /></el-form-item>
        <el-form-item label="数量"><el-input v-model="importOrderForm.qty" type="number" placeholder=">0" /></el-form-item>
        <el-form-item label="币种"><el-select v-model="importOrderForm.currency" style="width:100%"><el-option label="AED" value="AED" /><el-option label="SAR" value="SAR" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="importOrderOpen=false">取消</el-button><el-button type="primary" @click="submitImportOrder">确定</el-button></template>
    </el-dialog>

    <!-- 妥投弹窗 -->
    <el-dialog title="妥投出库（FIFO）" v-model="deliverOpen" width="440px" append-to-body>
      <el-form ref="deliverFormRef" :model="deliverForm" label-width="100px" size="small">
        <el-form-item label="订单行ID"><el-input v-model="deliverForm.salOrderItemId" type="number" readonly /></el-form-item>
        <el-form-item label="妥投数量"><el-input v-model="deliverForm.qty" type="number" placeholder=">0，≤可投量" /></el-form-item>
        <el-form-item label="站点"><el-input v-model="deliverForm.siteCode" readonly /></el-form-item>
      </el-form>
      <template #footer><el-button @click="deliverOpen=false">取消</el-button><el-button type="primary" @click="submitDeliver">确定</el-button></template>
    </el-dialog>

    <!-- 退货导入弹窗 -->
    <el-dialog title="退货入账" v-model="importReturnOpen" width="520px" append-to-body>
      <el-form ref="importReturnFormRef" :model="importReturnForm" label-width="110px" size="small">
        <el-form-item label="站点" required><el-select v-model="importReturnForm.siteCode" placeholder="请选择授权站点" style="width:100%"><el-option v-for="s in siteOptions" :key="s.value" :label="s.label" :value="s.value" /></el-select></el-form-item>
        <el-form-item label="订单号"><el-input v-model="importReturnForm.orderNo" placeholder="父订单号" /></el-form-item>
        <el-form-item label="平台退货号"><el-input v-model="importReturnForm.sourceReturnNo" placeholder="平台退货单号" /></el-form-item>
        <el-form-item label="订单行ID"><el-input v-model="importReturnForm.salOrderItemId" type="number" placeholder="父订单行 id" /></el-form-item>
        <el-form-item label="退货数量"><el-input v-model="importReturnForm.qty" type="number" placeholder=">0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="importReturnOpen=false">取消</el-button><el-button type="primary" @click="submitImportReturn">确定</el-button></template>
    </el-dialog>

    <!-- 红冲弹窗 -->
    <el-dialog title="退货红冲（冲回原 SALE 消耗）" v-model="reverseOpen" width="480px" append-to-body>
      <el-form ref="reverseFormRef" :model="reverseForm" label-width="120px" size="small">
        <el-form-item label="退货单ID"><el-input v-model="reverseForm.salReturnId" type="number" readonly /></el-form-item>
        <el-form-item label="原消耗ID"><el-input v-model="reverseForm.originalConsumptionId" type="number" placeholder="sign=+1 的 SALE 消耗行 id" /></el-form-item>
        <el-form-item label="红冲数量"><el-input v-model="reverseForm.qty" type="number" placeholder=">0，≤原消耗量" /></el-form-item>
        <el-form-item label="站点"><el-input v-model="reverseForm.siteCode" readonly /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reverseOpen=false">取消</el-button><el-button type="primary" @click="submitReverse">确定</el-button></template>
    </el-dialog>

    <!-- 分级处置弹窗 -->
    <el-dialog title="A/B/C 分级处置" v-model="disposeOpen" width="520px" append-to-body>
      <el-form ref="disposeFormRef" :model="disposeForm" label-width="110px" size="small">
        <el-form-item label="退货单ID"><el-input v-model="disposeForm.salReturnId" type="number" readonly /></el-form-item>
        <el-form-item label="分级">
          <el-select v-model="disposeForm.grade" style="width:100%"><el-option label="A-二次上架" value="A" /><el-option label="B-降级" value="B" /><el-option label="C-报废" value="C" /></el-select>
        </el-form-item>
        <el-form-item label="上架成本(A)"><el-input v-model="disposeForm.restockCost" type="number" placeholder="留空取原成本；填入须与原成本一致，偏离将被拒绝" /><div class="el-form-item__error" style="position:static;line-height:1.4">PRD return_restock_cost=ORIGINAL：资产转移不产生损益，重估需另行授权</div></el-form-item>
        <el-form-item label="赔偿金额"><el-input v-model="disposeForm.compensationAmount" type="number" placeholder="仅登记，M6 确认一次" /></el-form-item>
        <el-form-item label="处置数量"><el-input v-model="disposeForm.qty" type="number" placeholder="可选，留空取退货单量" /></el-form-item>
        <el-form-item label="站点"><el-input v-model="disposeForm.siteCode" readonly /></el-form-item>
      </el-form>
      <template #footer><el-button @click="disposeOpen=false">取消</el-button><el-button type="primary" @click="submitDispose">确定</el-button></template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="详情" v-model="detailOpen" width="640px" append-to-body>
      <pre style="font-size:12px;max-height:420px;overflow:auto">{{ detailJson }}</pre>
    </el-dialog>
  </div>
</template>

<script setup name="SalesFlow">
import { useSiteScope } from "@/composables/useSiteScope"
import { ref, reactive, getCurrentInstance } from 'vue'
import {
  importOrder, deliverOrder, importReturn, reverseReturn, disposeReturn,
  listOrders, getOrder, listReturns, getReturn
} from '@/api/noon/sales/salesFlow'

const { proxy } = getCurrentInstance()
const activeTab = ref('order')
const showSearch = ref(true)
const loading = ref(false)

const orderStatuses = ['IMPORTED', 'DELIVERED', 'RETURNED', 'CANCELLED']
const grades = ['A', 'B', 'C']

const orderQuery = ref({ pageNum: 1, pageSize: 10, siteCode: null, orderNo: null, status: null })
const returnQuery = ref({ pageNum: 1, pageSize: 10, siteCode: null, orderNo: null, grade: null })
const orderRows = ref([])
const returnRows = ref([])
const orderTotal = ref(0)
const returnTotal = ref(0)
const { siteFilter, siteLabel, siteOptions } = useSiteScope(() => {
  orderQuery.value.siteCode = siteFilter.value || null
  returnQuery.value.siteCode = siteFilter.value || null
  orderQuery.value.pageNum = 1
  returnQuery.value.pageNum = 1
  handleTab()
})
orderQuery.value.siteCode = siteFilter.value || null
returnQuery.value.siteCode = siteFilter.value || null

function newRequestId() { return 'm5-' + Date.now() + '-' + Math.random().toString(16).slice(2, 10) }

function getOrders() {
  loading.value = true
  listOrders({ ...orderQuery.value, siteCode: siteFilter.value || undefined }).then(res => {
    const p = res.data || {}
    orderRows.value = p.rows || []; orderTotal.value = p.total || 0
  }).catch(() => {}).finally(() => { loading.value = false })
}
function getReturns() {
  loading.value = true
  listReturns({ ...returnQuery.value, siteCode: siteFilter.value || undefined }).then(res => {
    const p = res.data || {}
    returnRows.value = p.rows || []; returnTotal.value = p.total || 0
  }).catch(() => {}).finally(() => { loading.value = false })
}
function handleTab() { activeTab.value === 'order' ? getOrders() : getReturns() }
function resetOrderQuery() { orderQuery.value = { pageNum: 1, pageSize: 10, siteCode: null, orderNo: null, status: null }; getOrders() }
function resetReturnQuery() { returnQuery.value = { pageNum: 1, pageSize: 10, siteCode: null, orderNo: null, grade: null }; getReturns() }
getOrders()

// 订单导入
const importOrderOpen = ref(false)
const importOrderForm = ref({})
function openImportOrder() {
  importOrderForm.value = { siteCode: siteFilter.value || null, orderNo: '', storeId: null, skuId: null, partnerSku: '', grossAmount: null, qty: null, currency: 'AED' }
  importOrderOpen.value = true
}
function submitImportOrder() {
  const f = importOrderForm.value
  if (!f.siteCode) { proxy.$modal.msgWarning('请选择授权站点'); return }
  if (!f.orderNo || !f.storeId || !f.skuId || !f.partnerSku || f.grossAmount === null || !(f.qty > 0)) { proxy.$modal.msgWarning('请填全订单字段且数量>0'); return }
  importOrder({ ...f, sourceChannel: 'MANUAL', requestId: newRequestId() }).then(() => {
    proxy.$modal.msgSuccess('导入成功'); importOrderOpen.value = false; getOrders()
  }).catch(e => proxy.$modal.msgError((e.response && e.response.data && e.response.data.msg) || '导入失败'))
}

// 妥投
const deliverOpen = ref(false)
const deliverForm = ref({})
function openDeliver(row) { deliverForm.value = { salOrderItemId: row.itemId || row.id, qty: null, siteCode: row.siteCode }; deliverOpen.value = true }
function submitDeliver() {
  const f = deliverForm.value
  if (!(f.qty > 0)) { proxy.$modal.msgWarning('妥投数量必须>0'); return }
  deliverOrder({ ...f, requestId: newRequestId() }).then(() => {
    proxy.$modal.msgSuccess('妥投成功'); deliverOpen.value = false; getOrders()
  }).catch(e => proxy.$modal.msgError((e.response && e.response.data && e.response.data.msg) || '妥投失败'))
}

// 退货导入
const importReturnOpen = ref(false)
const importReturnForm = ref({})
function openImportReturn() {
  importReturnForm.value = { siteCode: siteFilter.value || null, orderNo: '', sourceReturnNo: '', salOrderItemId: null, qty: null }
  importReturnOpen.value = true
}
function submitImportReturn() {
  const f = importReturnForm.value
  if (!f.siteCode) { proxy.$modal.msgWarning('请选择授权站点'); return }
  if (!f.orderNo || !f.sourceReturnNo || !f.salOrderItemId || !(f.qty > 0)) { proxy.$modal.msgWarning('请填全退货字段且数量>0'); return }
  importReturn({ ...f, requestId: newRequestId() }).then(() => {
    proxy.$modal.msgSuccess('退货入账成功'); importReturnOpen.value = false; getReturns()
  }).catch(e => proxy.$modal.msgError((e.response && e.response.data && e.response.data.msg) || '退货入账失败'))
}

// 红冲
const reverseOpen = ref(false)
const reverseForm = ref({})
function openReverse(row) { reverseForm.value = { salReturnId: row.id, originalConsumptionId: null, qty: row.qty, siteCode: row.siteCode }; reverseOpen.value = true }
function submitReverse() {
  const f = reverseForm.value
  if (!f.originalConsumptionId || !(f.qty > 0)) { proxy.$modal.msgWarning('请填原消耗ID且数量>0'); return }
  reverseReturn({ ...f, requestId: newRequestId() }).then(() => {
    proxy.$modal.msgSuccess('红冲成功'); reverseOpen.value = false; getReturns()
  }).catch(e => proxy.$modal.msgError((e.response && e.response.data && e.response.data.msg) || '红冲失败'))
}

// 分级处置
const disposeOpen = ref(false)
const disposeForm = ref({})
function openDispose(row) { disposeForm.value = { salReturnId: row.id, grade: 'B', restockCost: null, compensationAmount: null, qty: null, siteCode: row.siteCode }; disposeOpen.value = true }
function submitDispose() {
  const f = disposeForm.value
  if (!f.grade) { proxy.$modal.msgWarning('请选择分级'); return }
  disposeReturn({ ...f, requestId: newRequestId() }).then(() => {
    proxy.$modal.msgSuccess('处置成功'); disposeOpen.value = false; getReturns()
  }).catch(e => proxy.$modal.msgError((e.response && e.response.data && e.response.data.msg) || '处置失败'))
}

// 详情
const detailOpen = ref(false)
const detailJson = ref('')
function showOrderDetail(row) { getOrder(row.id).then(res => { detailJson.value = JSON.stringify(res.data, null, 2); detailOpen.value = true }) }
function showReturnDetail(row) { getReturn(row.id).then(res => { detailJson.value = JSON.stringify(res.data, null, 2); detailOpen.value = true }) }
</script>
