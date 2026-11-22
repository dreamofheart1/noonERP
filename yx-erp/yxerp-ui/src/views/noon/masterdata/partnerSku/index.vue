<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="110px">
        <el-form-item label="Partner SKU" prop="partnerSku">
          <el-input v-model="queryParams.partnerSku" placeholder="请输入Partner SKU" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="SKU ID" prop="skuId">
          <el-input v-model="queryParams.skuId" placeholder="请输入SKU ID" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="站点" prop="siteCode">
          <el-select v-model="queryParams.siteCode" placeholder="请选择站点" clearable style="width:200px">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:200px">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:masterdata:partnerSku:save']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:masterdata:partnerSku:save']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:masterdata:partnerSku:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:masterdata:partnerSku:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="partnerSkuList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="Partner SKU" align="center" prop="partnerSku" show-overflow-tooltip />
      <el-table-column label="SKU ID" align="center" prop="skuId" show-overflow-tooltip />
      <el-table-column label="站点" align="center" prop="siteCode">
        <template #default="scope">{{ { AE:'AE', SA:'SA' }[scope.row.siteCode] || scope.row.siteCode }}</template>
      </el-table-column>
      <el-table-column label="业务模式" align="center" prop="businessModel" show-overflow-tooltip />
      <el-table-column label="Offer编码" align="center" prop="offerCode" show-overflow-tooltip />
      <el-table-column label="条形码" align="center" prop="barcode" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:masterdata:partnerSku:save']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:masterdata:partnerSku:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:masterdata:partnerSku:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="partnerSkuRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="Partner SKU" prop="partnerSku">
        <el-input v-model="form.partnerSku" placeholder="请输入Partner SKU" />
      </el-form-item>
      <el-form-item label="SKU ID" prop="skuId">
        <el-input-number v-model="form.skuId" :min="1" :precision="0" style="width:100%" placeholder="请输入SKU ID" />
      </el-form-item>
      <el-form-item label="站点" prop="siteCode">
        <el-select v-model="form.siteCode" placeholder="请选择站点" style="width:100%">
          <el-option label="AE" value="AE" />
          <el-option label="SA" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item label="业务模式" prop="businessModel">
        <el-input v-model="form.businessModel" placeholder="请输入业务模式" />
      </el-form-item>
      <el-form-item label="Offer编码" prop="offerCode">
        <el-input v-model="form.offerCode" placeholder="请输入Offer编码" />
      </el-form-item>
      <el-form-item label="条形码" prop="barcode">
        <el-input v-model="form.barcode" placeholder="请输入条形码" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="请选择状态" style="width:100%">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
      </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="PartnerSku">
import { listPartnerSku, getPartnerSku, addPartnerSku, updatePartnerSku, changePartnerSkuStatus } from "@/api/noon/masterdata/partnerSku"
const { proxy } = getCurrentInstance()
const partnerSkuList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const total = ref(0)
const title = ref("")
const data = reactive({
  form: {},
  queryParams: { pageNum: 1, limit: 10,
    partnerSku: null,
    skuId: null,
    siteCode: null,
    status: null,
  },
  rules: {
    partnerSku: [{ required: true, message: 'Partner SKU不能为空', trigger: 'blur' }],
    skuId: [{ required: true, message: 'SKU ID不能为空', trigger: 'blur' }],
    siteCode: [{ required: true, message: '站点不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'blur' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listPartnerSku(params).then(res => {
    const payload = res.data || {}
    partnerSkuList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    partnerSku: null,
    skuId: null,
    siteCode: null,
    businessModel: null,
    offerCode: null,
    barcode: null,
    status: null,
  }
  proxy.resetForm("partnerSkuRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加Partner SKU映射" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getPartnerSku(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改Partner SKU映射" })
}
function submitForm() {
  proxy.$refs["partnerSkuRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updatePartnerSku(form.value.id, form.value) : addPartnerSku(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changePartnerSkuStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changePartnerSkuStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changePartnerSkuStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
