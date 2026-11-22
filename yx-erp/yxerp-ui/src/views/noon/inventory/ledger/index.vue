<!--
  M4 四态库存台账（只读）
  基于若依代码生成器 inv_stock_ledger 骨架改造：查询区 el-form + right-toolbar + el-table + pagination、
  <script setup> 的 data/queryParams/getList/handleQuery/resetQuery 结构均沿用生成骨架（见
  docs/verification/m4-inventory/generated/ADOPTION.md）。按只读台账删去增改删，字段与 API 指向
  业务接口 /noon/inventory/ledger，分页按 PageResult 契约把 pageNum/pageSize 换算为 offset/limit。
-->
<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="80px">
      <el-form-item label="站点" prop="siteCode">
        <el-select v-model="queryParams.siteCode" clearable placeholder="全部授权站点" style="width:170px">
          <el-option v-for="s in siteOptions" :key="s" :label="s" :value="s" />
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
      <el-table-column label="SKU" align="center" prop="skuId" width="110" />
      <el-table-column label="仓库ID" align="center" prop="warehouseId" width="110" />
      <el-table-column label="站点" align="center" prop="siteCode" width="90" />
      <el-table-column label="可用" align="center" prop="qtyAvailable" width="100">
        <template #default="scope"><span class="available">{{ scope.row.qtyAvailable }}</span></template>
      </el-table-column>
      <el-table-column label="在途" align="center" prop="qtyInTransit" width="100" />
      <el-table-column label="预留" align="center" prop="qtyReserved" width="100" />
      <el-table-column label="待检" align="center" prop="qtyUnsellable" width="100" />
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
import { listLedger } from "@/api/noon/inventory/ledger"

const { proxy } = getCurrentInstance()

const ledgerList = ref([])
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const siteOptions = ref(["AE", "SA"])

const data = reactive({
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    siteCode: null
  }
})
const { queryParams } = toRefs(data)

/** 查询台账列表（沿用生成器 getList 结构；换算 PageResult 分页） */
function getList() {
  loading.value = true
  const params = {
    offset: (queryParams.value.pageNum - 1) * queryParams.value.pageSize,
    limit: queryParams.value.pageSize,
    siteCode: queryParams.value.siteCode
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
  handleQuery()
}

getList()
</script>

<style scoped>
.available { color: #67c23a; font-weight: 600; }
</style>
