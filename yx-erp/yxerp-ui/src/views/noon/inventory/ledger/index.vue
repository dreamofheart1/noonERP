<!--
  M4 四态库存台账（只读）
  基于若依代码生成器 inv_stock_ledger 骨架改造：查询区 el-form + right-toolbar + el-table + pagination、
  <script setup> 的 data/queryParams/getList/handleQuery/resetQuery 结构均沿用生成骨架（见
  docs/verification/m4-inventory/generated/ADOPTION.md）。按只读台账删去增改删，字段与 API 指向
  业务接口 /noon/inventory/ledger，分页按 PageResult 契约把 pageNum/pageSize 换算为 offset/limit。
-->
<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 四态台账（可用 / 在途 / 锁定 / 不可售）由单据过账生成，本页只读；库存推送（绝对值 set）未接入`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />
  <el-alert title="本页为系统内部库存台账，来源于采购收货/入库/ASN 过账；与 Noon 官方平台的实时库存是两个口径，不代表平台当前可售量。FBN 官方仓在此仅为系统内部记录，不代表平台已建仓/已预约。" type="warning" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="90px">
      <el-form-item label="站点">
        <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
      </el-form-item>
      <el-form-item label="仓库">
        <el-select v-model="queryParams.warehouseId" placeholder="全部仓库" clearable filterable style="width: 220px">
          <el-option v-for="w in warehouseOptions" :key="w.id" :label="w.label" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="ledgerList" border>
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="SKU 编码" align="center" prop="skuCode" min-width="130">
        <template #default="scope">{{ scope.row.skuCode || (skuMap[scope.row.skuId] && skuMap[scope.row.skuId].skuCode) || ('SKU#' + scope.row.skuId) }}</template>
      </el-table-column>
      <el-table-column label="商品名称" align="center" min-width="180">
        <template #default="scope">
          <span>{{ skuName(scope.row) }}</span>
          <div v-if="scope.row.nameAr" class="secondary">{{ scope.row.nameAr }}</div>
        </template>
      </el-table-column>
      <el-table-column label="SKU ID" align="center" prop="skuId" width="90" />
      <el-table-column label="仓库" align="center" min-width="150">
        <template #default="scope">{{ (warehouseMap[scope.row.warehouseId] && (warehouseMap[scope.row.warehouseId].warehouseName || warehouseMap[scope.row.warehouseId].warehouseCode)) || scope.row.warehouseName || ('仓库#' + scope.row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column label="仓库ID" align="center" prop="warehouseId" width="90" />
      <el-table-column label="站点" align="center" prop="siteCode" width="80" />
      <el-table-column label="可用" align="center" prop="qtyAvailable" width="90">
        <template #default="scope"><span class="available">{{ scope.row.qtyAvailable }}</span></template>
      </el-table-column>
      <el-table-column label="在途" align="center" prop="qtyInTransit" width="90" />
      <el-table-column label="锁定" align="center" prop="qtyReserved" width="90" />
      <el-table-column label="不可售" align="center" prop="qtyUnsellable" width="90" />
      <el-table-column label="更新时间" align="center" prop="updatedAt" min-width="170" />
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.pageNum"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </div>
</template>

<script setup name="LedgerInventory">
import { useSiteScope } from "@/composables/useSiteScope"
import { listLedger } from "@/api/noon/inventory/ledger"
import { listSku } from "@/api/noon/masterdata/sku"
import { listWarehouse } from "@/api/noon/masterdata/warehouse"

const { proxy } = getCurrentInstance()

const ledgerList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const skuMap = ref({})
const warehouseMap = ref({})
const warehouseOptions = ref([])

const data = reactive({
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    siteCode: null,
    warehouseId: null
  }
})
const { queryParams } = toRefs(data)

/** SKU 显示名：英文优先，无则阿拉伯语，仍无则回退编码 */
function skuName(row) {
  const s = skuMap.value[row.skuId]
  if (!s) return row.nameEn || ''
  return s.nameEn || s.nameAr || s.skuCode || ''
}

/** 加载 SKU / 仓库主数据，供表格显示名称与仓库筛选下拉（按站点授权过滤） */
function loadMasterData() {
  const site = siteFilter.value || undefined
  listSku({ offset: 0, limit: 200, siteCode: site }).then(res => {
    const rows = (res.data && res.data.rows) || []
    const map = {}
    rows.forEach(s => { map[s.id] = s })
    skuMap.value = map
  }).catch(() => {})
  listWarehouse({ offset: 0, limit: 200, siteCode: site }).then(res => {
    const rows = (res.data && res.data.rows) || []
    const map = {}
    const opts = []
    rows.forEach(w => {
      map[w.id] = w
      opts.push({ id: w.id, label: w.warehouseName ? `${w.warehouseCode} · ${w.warehouseName}` : w.warehouseCode })
    })
    warehouseMap.value = map
    warehouseOptions.value = opts
  }).catch(() => {})
}

/** 查询台账列表（沿用生成器 getList 结构；换算 PageResult 分页） */
const { siteFilter, siteLabel } = useSiteScope(() => {
  if (queryParams.value) {
    queryParams.value.siteCode = siteFilter.value || null
    queryParams.value.warehouseId = null
    queryParams.value.pageNum = 1
  }
  loadMasterData()
  getList()
})
if (queryParams.value) queryParams.value.siteCode = siteFilter.value || null
function getList() {
  loading.value = true
  const params = {
    offset: (queryParams.value.pageNum - 1) * queryParams.value.pageSize,
    limit: queryParams.value.pageSize,
    siteCode: siteFilter.value || undefined,
    warehouseId: queryParams.value.warehouseId || undefined
  }
  listLedger(params).then(response => {
    const payload = response.data || {}
    ledgerList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}

/** 搜索按钮操作（沿用生成器） */
function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

/** 重置按钮操作（沿用生成器） */
function resetQuery() {
  proxy.resetForm("queryRef")
  queryParams.value.warehouseId = null
  handleQuery()
}

loadMasterData()
getList()
</script>

<style scoped>
.available { color: #67c23a; font-weight: 600; }
.secondary { color: #909399; font-size: 12px; line-height: 1.3; }
</style>
