<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 收货记录为只读台账，成本只含采购价；未实现的费用分摊待接入`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-alert
      title="M3 隔离演示数据，成本口径只含采购价不含头程费用；收货不生成库存批次。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="86px">
      <el-form-item label="采购单ID" prop="orderId">
        <el-input-number v-model="queryParams.orderId" :min="1" :precision="0" controls-position="right" style="width:180px" placeholder="请输入采购单ID" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="站点">
        <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="receiptList" border>
      <el-table-column label="ID" align="center" prop="id" width="90" />
      <el-table-column label="采购单ID" align="center" prop="orderId" width="120" />
      <el-table-column label="明细ID" align="center" prop="orderItemId" width="120" />
      <el-table-column label="收货数量" align="center" prop="qty" width="120" />
      <el-table-column label="单位成本(CNY)" align="center" prop="unitCostCny" width="150" />
      <el-table-column label="收货时间" align="center" prop="receivedAt" width="180" />
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
  </div>
</template>

<script setup name="ReceiptPurchasing">
import { useSiteScope } from "@/composables/useSiteScope"
import { listReceipt } from "@/api/noon/purchasing/receipt"
const { proxy } = getCurrentInstance()
const receiptList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const data = reactive({
  queryParams: { pageNum: 1, limit: 10,
    orderId: null,
  }
})
const { queryParams } = toRefs(data)
const { siteFilter, siteLabel } = useSiteScope(() => {
  if (queryParams.value) queryParams.value.siteCode = siteFilter.value || null
  if (queryParams.value) queryParams.value.pageNum = 1
  getList()
})
if (queryParams.value) queryParams.value.siteCode = siteFilter.value || null
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  params.siteCode = siteFilter.value || undefined
  listReceipt(params).then(res => {
    const payload = res.data || {}
    receiptList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
getList()
</script>
