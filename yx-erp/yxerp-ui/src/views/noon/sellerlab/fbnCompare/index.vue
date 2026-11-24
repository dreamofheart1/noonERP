<template>
  <div class="app-container">
    <el-alert type="warning" :closable="false" show-icon>
      Noon Seller Lab 非公开内部接口，正式支持与长期稳定性未确认；单进程内约 3 秒限速，不代表官方阈值。
      官方数量为只读快照，差异不认定为丢货，不会覆盖 ERP 账目。未同步/失败不等于 0。
    </el-alert>

    <el-form :inline="true" style="margin-top:12px">
      <el-form-item label="站点">
        <el-select v-model="queryParams.siteCode" style="width:100px">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="getList">查询</el-button>
        <el-button type="warning" icon="RefreshRight" :loading="syncing" @click="handleSync"
                   v-hasPermi="['noon:masterdata:sku:save']">从Noon拉取</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="Partner SKU" prop="partnerSku" show-overflow-tooltip />
      <el-table-column label="Noon SKU" prop="noonSku" show-overflow-tooltip />
      <el-table-column label="系统SKU" prop="skuCode" show-overflow-tooltip />
      <el-table-column label="官方可售" prop="officialSaleable" align="center" />
      <el-table-column label="官方不可售" prop="officialNonSaleable" align="center" />
      <el-table-column label="系统可用" align="center">
        <template #default="s">{{ s.row.systemAvailable == null ? '未同步' : s.row.systemAvailable }}</template>
      </el-table-column>
      <el-table-column label="系统不可售" align="center">
        <template #default="s">{{ s.row.systemUnsellable == null ? '未同步' : s.row.systemUnsellable }}</template>
      </el-table-column>
      <el-table-column label="差异(可售)" align="center">
        <template #default="s">{{ s.row.systemAvailable == null ? '不可比较' : s.row.officialSaleable - s.row.systemAvailable }}</template>
      </el-table-column>
      <el-table-column label="官方仓" prop="warehouseCode" show-overflow-tooltip />
      <el-table-column label="官方数据时间" prop="capturedAt" width="170" />
      <el-table-column label="说明" prop="note" show-overflow-tooltip />
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit"
                @pagination="getList" />
  </div>
</template>

<script setup name="NoonFbnCompare">
import { listFbnCompare, syncFbnInventory } from '@/api/noon/sellerlab'
const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(true)
const syncing = ref(false)
const queryParams = ref({ pageNum: 1, limit: 50, siteCode: 'AE' })

function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  listFbnCompare({ ...rest, offset: (pageNum - 1) * queryParams.value.limit }).then(res => {
    const p = res.data || {}
    rows.value = p.rows || []
    total.value = Number(p.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleSync() {
  proxy.$modal.confirm('将按约3秒间隔只读拉取官方FBN库存快照，不会覆盖ERP账目。是否继续？').then(() => {
    syncing.value = true
    syncFbnInventory(queryParams.value.siteCode).then(res => {
      syncing.value = false
      proxy.$modal.msgSuccess(`平台${res.data?.platformRows || 0}条，写入${res.data?.writtenRows || 0}条`)
      getList()
    }).catch(() => { syncing.value = false })
  }).catch(() => {})
}
getList()
</script>
