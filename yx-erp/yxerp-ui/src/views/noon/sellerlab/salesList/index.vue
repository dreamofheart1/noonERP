<template>
  <div class="app-container">
    <el-alert type="warning" :closable="false" show-icon>
      item_nr 仅为 Noon 平台销售明细标识，不等于整张订单号；同站点按 (站点, item_nr) 去重，状态更新覆盖同键记录。
      非公开内部接口、单进程约 3 秒限速；未同步/失败不等于零。
    </el-alert>

    <el-form :inline="true" style="margin-top:12px">
      <el-form-item label="站点">
        <el-select v-model="queryParams.siteCode" style="width:100px">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item label="开始日期"><el-date-picker v-model="dateRange[0]" type="date" value-format="YYYY-MM-DD" placeholder="开始" /></el-form-item>
      <el-form-item label="结束日期"><el-date-picker v-model="dateRange[1]" type="date" value-format="YYYY-MM-DD" placeholder="结束" /></el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="getList">查询</el-button>
        <el-button type="warning" icon="RefreshRight" :loading="syncing" @click="handleSync"
                   v-hasPermi="['noon:masterdata:sku:save']">从Noon拉取</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="编号(item_nr)" prop="itemNr" width="200" show-overflow-tooltip />
      <el-table-column label="站点" prop="siteCode" width="70" align="center" />
      <el-table-column label="Partner SKU" prop="partnerSku" show-overflow-tooltip />
      <el-table-column label="状态" prop="status" width="110" align="center" />
      <el-table-column label="履约模式" prop="fulfillmentModel" width="130" show-overflow-tooltip />
      <el-table-column label="下单时间" prop="orderTs" width="170" />
      <el-table-column label="发运时间" prop="shipmentTs" width="170" />
      <el-table-column label="送达时间" prop="deliveredTs" width="170" />
      <el-table-column label="运单号" prop="awbNr" width="150" show-overflow-tooltip />
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit"
                @pagination="getList" />
  </div>
</template>

<script setup name="NoonSalesList">
import { listSalesItems, syncSalesItems } from '@/api/noon/sellerlab'
const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(true)
const syncing = ref(false)
const dateRange = ref([])
const queryParams = ref({ pageNum: 1, limit: 50, siteCode: 'AE' })

function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  listSalesItems({ ...rest, offset: (pageNum - 1) * queryParams.value.limit }).then(res => {
    const p = res.data || {}
    rows.value = p.rows || []
    total.value = Number(p.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleSync() {
  if (!dateRange.value || dateRange.value.length !== 2) { proxy.$modal.msgWarning('请选择日期区间'); return }
  proxy.$modal.confirm('将按约3秒间隔只读拉取官方Sales明细，按(站点,item_nr)幂等更新，不覆盖ERP账目。是否继续？').then(() => {
    syncing.value = true
    syncSalesItems({ siteCode: queryParams.value.siteCode, fromDate: dateRange.value[0], toDate: dateRange.value[1], maxPages: 5 })
      .then(res => {
        syncing.value = false
        proxy.$modal.msgSuccess(`平台${res.data?.platformRows || 0}条，写入${res.data?.writtenRows || 0}条`)
        getList()
      }).catch(() => { syncing.value = false })
  }).catch(() => {})
}
getList()
</script>
