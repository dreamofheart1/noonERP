<template>
  <div class="app-container">
    <el-alert
      title="M3 隔离演示数据，成本口径只含采购价不含头程费用；收货不生成库存批次。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="100px">
      <el-form-item label="供应商编号" prop="supplierNo">
        <el-input v-model="queryParams.supplierNo" placeholder="请输入供应商编号" clearable style="width:200px" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="供应商名称" prop="supplierName">
        <el-input v-model="queryParams.supplierName" placeholder="请输入供应商名称" clearable style="width:200px" @keyup.enter="handleQuery" />
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
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:purchasing:supplier:add']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:purchasing:supplier:edit']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:purchasing:supplier:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:purchasing:supplier:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="supplierList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="供应商编号" align="center" prop="supplierNo" show-overflow-tooltip />
      <el-table-column label="供应商名称" align="center" prop="supplierName" show-overflow-tooltip />
      <el-table-column label="联系信息" align="center" prop="contactJson" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status" width="90">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:purchasing:supplier:edit']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:purchasing:supplier:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:purchasing:supplier:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="supplierRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="供应商编号" prop="supplierNo">
          <el-input v-model="form.supplierNo" placeholder="请输入供应商编号" />
        </el-form-item>
        <el-form-item label="供应商名称" prop="supplierName">
          <el-input v-model="form.supplierName" placeholder="请输入供应商名称" />
        </el-form-item>
        <el-form-item label="联系信息" prop="contactJson">
          <el-input v-model="form.contactJson" type="textarea" :rows="3" placeholder="请输入合法 JSON 字符串，可留空" />
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

<script setup name="SupplierPurchasing">
import { listSupplier, getSupplier, addSupplier, updateSupplier, changeSupplierStatus } from "@/api/noon/purchasing/supplier"
const { proxy } = getCurrentInstance()
const supplierList = ref([])
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
    supplierNo: null,
    supplierName: null,
    status: null,
  },
  rules: {
    supplierNo: [{ required: true, message: '供应商编号不能为空', trigger: 'blur' }],
    supplierName: [{ required: true, message: '供应商名称不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'change' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listSupplier(params).then(res => {
    const payload = res.data || {}
    supplierList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    id: null,
    supplierNo: null,
    supplierName: null,
    contactJson: null,
    status: null,
  }
  proxy.resetForm("supplierRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加供应商" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getSupplier(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改供应商" })
}
function submitForm() {
  proxy.$refs["supplierRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateSupplier(form.value.id, form.value) : addSupplier(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changeSupplierStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changeSupplierStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changeSupplierStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
