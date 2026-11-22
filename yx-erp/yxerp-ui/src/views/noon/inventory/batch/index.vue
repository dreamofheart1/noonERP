<!--
  M4 FIFO 成本批次（只读台账）
  基于若依代码生成器 inv_batch 骨架改造：查询区 el-form + right-toolbar + el-table + pagination、
  <script setup> 的 data/queryParams/getList/handleQuery/resetQuery 结构均沿用生成骨架（见
  docs/verification/m4-inventory/generated/ADOPTION.md）。按只读台账删去增改删，字段与 API 指向
  业务接口 /noon/inventory/batch，分页按 PageResult 契约把 pageNum/pageSize 换算为 offset/limit。
-->
<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · FIFO 批次按 received_at/batch_id 出库；COGS 唯一来源 inv_batch_consumption`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="80px">
      <el-form-item label="站点">
        <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
      </el-form-item>
      <el-form-item label="SKU" prop="skuId">
        <el-input-number v-model="queryParams.skuId" :min="1" :precision="0" controls-position="right" style="width:170px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="batchList" border>
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="批次号" align="center" prop="batchNo" min-width="200" />
      <el-table-column label="SKU" align="center" prop="skuId" width="100" />
      <el-table-column label="仓库ID" align="center" prop="warehouseId" width="100" />
      <el-table-column label="站点" align="center" prop="siteCode" width="80" />
      <el-table-column label="入库量" align="center" prop="qtyIn" width="90" />
      <el-table-column label="剩余量" align="center" prop="qtyRemaining" width="90" />
      <el-table-column label="单位成本" align="center" prop="unitCost" width="110" />
      <el-table-column label="状态" align="center" prop="status" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'OPEN' ? 'success' : 'info'">{{ scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="入库时间" align="center" prop="receivedAt" min-width="170" />
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

<script setup name="BatchInventory">
import { useSiteScope } from "@/composables/useSiteScope"
import { listBatch } from "@/api/noon/inventory/batch"

const { proxy } = getCurrentInstance()

const batchList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)

const data = reactive({
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    siteCode: null,
    skuId: null
  }
})
const { queryParams } = toRefs(data)

/** 查询批次列表（沿用生成器 getList 结构；换算 PageResult 分页） */
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
    siteCode: siteFilter.value || undefined,
    skuId: queryParams.value.skuId
  }
  listBatch(params).then(response => {
    const payload = response.data || {}
    batchList.value = Array.isArray(payload.rows) ? payload.rows : []
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
  queryParams.value.skuId = null
  handleQuery()
}

getList()
</script>
