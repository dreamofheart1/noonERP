<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="110px">
        <el-form-item label="店铺编号" prop="storeNo">
          <el-input v-model="queryParams.storeNo" placeholder="请输入店铺编号" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="英文名称" prop="nameEn">
          <el-input v-model="queryParams.nameEn" placeholder="请输入英文名称" clearable style="width:200px" @keyup.enter="handleQuery" />
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
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:masterdata:store:save']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:masterdata:store:save']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:masterdata:store:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:masterdata:store:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="storeList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="店铺编号" align="center" prop="storeNo" show-overflow-tooltip />
      <el-table-column label="Partner账号ID" align="center" prop="partnerId" show-overflow-tooltip />
      <el-table-column label="英文名称" align="center" prop="nameEn" show-overflow-tooltip />
      <el-table-column label="阿拉伯语名称" align="center" prop="nameAr" show-overflow-tooltip />
      <el-table-column label="执照主体" align="center" prop="legalEntity" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:masterdata:store:save']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:masterdata:store:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:masterdata:store:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="storeRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="店铺编号" prop="storeNo">
        <el-input v-model="form.storeNo" placeholder="请输入店铺编号" />
      </el-form-item>
      <el-form-item label="Partner账号ID" prop="partnerId">
        <el-input-number v-model="form.partnerId" :min="1" :precision="0" style="width:100%" placeholder="请输入Partner账号ID" />
      </el-form-item>
      <el-form-item label="英文名称" prop="nameEn">
        <el-input v-model="form.nameEn" placeholder="请输入英文名称" />
      </el-form-item>
      <el-form-item label="阿拉伯语名称" prop="nameAr">
        <el-input v-model="form.nameAr" placeholder="请输入阿拉伯语名称" />
      </el-form-item>
      <el-form-item label="执照主体" prop="legalEntity">
        <el-input v-model="form.legalEntity" placeholder="请输入执照主体" />
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

<script setup name="Store">
import { listStore, getStore, addStore, updateStore, changeStoreStatus } from "@/api/noon/masterdata/store"
const { proxy } = getCurrentInstance()
const storeList = ref([])
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
    storeNo: null,
    nameEn: null,
    status: null,
  },
  rules: {
    storeNo: [{ required: true, message: '店铺编号不能为空', trigger: 'blur' }],
    partnerId: [{ required: true, message: 'Partner账号ID不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'blur' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listStore(params).then(res => {
    const payload = res.data || {}
    storeList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    storeNo: null,
    partnerId: null,
    nameEn: null,
    nameAr: null,
    legalEntity: null,
    status: null,
  }
  proxy.resetForm("storeRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加店铺" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getStore(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改店铺" })
}
function submitForm() {
  proxy.$refs["storeRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateStore(form.value.id, form.value) : addStore(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changeStoreStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changeStoreStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changeStoreStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
