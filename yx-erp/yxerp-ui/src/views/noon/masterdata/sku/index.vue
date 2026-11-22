<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="110px">
        <el-form-item label="SKU编码" prop="skuCode">
          <el-input v-model="queryParams.skuCode" placeholder="请输入SKU编码" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="英文名" prop="nameEn">
          <el-input v-model="queryParams.nameEn" placeholder="请输入英文名" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="品牌" prop="brand">
          <el-input v-model="queryParams.brand" placeholder="请输入品牌" clearable style="width:200px" @keyup.enter="handleQuery" />
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
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:masterdata:sku:save']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:masterdata:sku:save']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:masterdata:sku:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:masterdata:sku:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="skuList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="SKU编码" align="center" prop="skuCode" show-overflow-tooltip />
      <el-table-column label="SPU编码" align="center" prop="spuId" show-overflow-tooltip />
      <el-table-column label="英文名" align="center" prop="nameEn" show-overflow-tooltip />
      <el-table-column label="阿拉伯语名" align="center" prop="nameAr" show-overflow-tooltip />
      <el-table-column label="Noon类目码" align="center" prop="categoryCode" show-overflow-tooltip />
      <el-table-column label="HS编码" align="center" prop="hsCode" show-overflow-tooltip />
      <el-table-column label="原产国" align="center" prop="originCountry" show-overflow-tooltip />
      <el-table-column label="品牌" align="center" prop="brand" show-overflow-tooltip />
      <el-table-column label="规格JSON" align="center" prop="specJson" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:masterdata:sku:save']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:masterdata:sku:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:masterdata:sku:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="skuRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="SKU编码" prop="skuCode">
        <el-input v-model="form.skuCode" placeholder="请输入SKU编码" />
      </el-form-item>
      <el-form-item label="SPU编码" prop="spuId">
        <el-input v-model="form.spuId" placeholder="请输入SPU编码" />
      </el-form-item>
      <el-form-item label="英文名" prop="nameEn">
        <el-input v-model="form.nameEn" placeholder="请输入英文名" />
      </el-form-item>
      <el-form-item label="阿拉伯语名" prop="nameAr">
        <el-input v-model="form.nameAr" placeholder="请输入阿拉伯语名" />
      </el-form-item>
      <el-form-item label="Noon类目码" prop="categoryCode">
        <el-input v-model="form.categoryCode" placeholder="请输入Noon类目码" />
      </el-form-item>
      <el-form-item label="HS编码" prop="hsCode">
        <el-input v-model="form.hsCode" placeholder="请输入HS编码" />
      </el-form-item>
      <el-form-item label="原产国" prop="originCountry">
        <el-input v-model="form.originCountry" placeholder="请输入原产国" />
      </el-form-item>
      <el-form-item label="品牌" prop="brand">
        <el-input v-model="form.brand" placeholder="请输入品牌" />
      </el-form-item>
      <el-form-item label="规格JSON" prop="specJson">
        <el-input v-model="form.specJson" placeholder="请输入规格JSON" />
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

<script setup name="Sku">
import { listSku, getSku, addSku, updateSku, changeSkuStatus } from "@/api/noon/masterdata/sku"
const { proxy } = getCurrentInstance()
const skuList = ref([])
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
    skuCode: null,
    nameEn: null,
    brand: null,
    status: null,
  },
  rules: {
    skuCode: [{ required: true, message: 'SKU编码不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'blur' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listSku(params).then(res => {
    const payload = res.data || {}
    skuList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    skuCode: null,
    spuId: null,
    nameEn: null,
    nameAr: null,
    categoryCode: null,
    hsCode: null,
    originCountry: null,
    brand: null,
    specJson: null,
    status: null,
  }
  proxy.resetForm("skuRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加SKU" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getSku(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改SKU" })
}
function submitForm() {
  proxy.$refs["skuRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateSku(form.value.id, form.value) : addSku(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changeSkuStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changeSkuStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changeSkuStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
