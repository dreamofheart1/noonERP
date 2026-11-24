<template>
  <div class="app-container">
    <el-alert type="warning" :closable="false" show-icon>
      平台 ASN 只读快照。平台 ASN 编号与 ERP 内部 ASN 编号分别保存，当前不自动关联、不生成入库、不过账；
      非公开内部接口、单进程约 3 秒限速，失败或截断不等同于完整数据。
    </el-alert>

    <el-form :inline="true" style="margin-top:12px">
      <el-form-item label="站点">
        <el-select v-model="queryParams.siteCode" style="width:100px">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item label="拉取页数"><el-input-number v-model="maxPages" :min="1" :max="100" style="width:110px" /></el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">查询</el-button>
        <el-button type="warning" icon="RefreshRight" :loading="syncing" @click="handleSync"
                   v-hasPermi="['noon:masterdata:sku:save']">从Noon拉取</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="平台ASN编号" prop="platformAsnNr" width="190" show-overflow-tooltip />
      <el-table-column label="ERP内部ASN" width="130" align="center">
        <template #default><span class="text-muted">未关联</span></template>
      </el-table-column>
      <el-table-column label="站点" prop="siteCode" width="70" align="center" />
      <el-table-column label="状态" prop="status" width="150" show-overflow-tooltip />
      <el-table-column label="目的仓" prop="dstWarehouseCode" width="150" show-overflow-tooltip />
      <el-table-column label="预期数" prop="expectedQty" align="center" />
      <el-table-column label="已收" prop="qtyReceived" align="center" />
      <el-table-column label="已上架" prop="qtyPutaway" align="center" />
      <el-table-column label="平台更新时间" prop="platformUpdatedAt" width="170" />
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit"
                @pagination="getList" />
  </div>
</template>

<script setup name="NoonAsnList">
import { listPlatformAsn, syncPlatformAsn } from '@/api/noon/sellerlab'
const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(true)
const syncing = ref(false)
const maxPages = ref(20)
const queryParams = ref({ pageNum: 1, limit: 50, siteCode: 'AE' })

function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  listPlatformAsn({ ...rest, offset: (pageNum - 1) * queryParams.value.limit }).then(res => {
    const p = res.data || {}
    rows.value = p.rows || []
    total.value = Number(p.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function handleSync() {
  proxy.$modal.confirm('将按约3秒间隔只读拉取平台ASN，按(站点,平台ASN编号)去重更新，不关联ERP内部ASN、不过账。是否继续？').then(() => {
    syncing.value = true
    syncPlatformAsn({ siteCode: queryParams.value.siteCode, maxPages: maxPages.value }).then(res => {
      syncing.value = false
      const r = res.data || {}
      const tail = r.truncated ? '（已达页数上限，可能只同步了部分，可增大拉取页数后继续）' : ''
      proxy.$modal.msgSuccess(`平台${r.platformRows || 0}条，写入${r.writtenRows || 0}条${tail}`)
      handleQuery()
    }).catch(() => { syncing.value = false })
  }).catch(() => {})
}
getList()
</script>

<style scoped>
.text-muted { color: #909399; font-size: 12px; }
</style>
