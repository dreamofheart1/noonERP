<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="110px">
        <el-form-item label="站点编码" prop="siteCode">
          <el-select v-model="queryParams.siteCode" placeholder="请选择站点编码" clearable style="width:200px">
          <el-option label="AE 阿联酋" value="AE" />
          <el-option label="SA 沙特" value="SA" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属店铺ID" prop="storeId">
          <el-input v-model="queryParams.storeId" placeholder="请输入所属店铺ID" clearable style="width:200px" @keyup.enter="handleQuery" />
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
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:masterdata:site:save']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:masterdata:site:save']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:masterdata:site:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:masterdata:site:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="siteList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="站点编码" align="center" prop="siteCode">
        <template #default="scope">{{ { AE:'AE 阿联酋', SA:'SA 沙特' }[scope.row.siteCode] || scope.row.siteCode }}</template>
      </el-table-column>
      <el-table-column label="所属店铺ID" align="center" prop="storeId" show-overflow-tooltip />
      <el-table-column label="国家" align="center" prop="country" show-overflow-tooltip />
      <el-table-column label="币种" align="center" prop="currency" show-overflow-tooltip />
      <el-table-column label="VAT税率" align="center" prop="vatRate" show-overflow-tooltip />
      <el-table-column label="时区" align="center" prop="timezone" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:masterdata:site:save']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:masterdata:site:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:masterdata:site:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="siteRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="站点编码" prop="siteCode">
        <el-select v-model="form.siteCode" placeholder="请选择站点编码" style="width:100%">
          <el-option label="AE 阿联酋" value="AE" />
          <el-option label="SA 沙特" value="SA" />
        </el-select>
      </el-form-item>
      <el-form-item label="所属店铺ID" prop="storeId">
        <el-input-number v-model="form.storeId" :min="1" :precision="0" style="width:100%" placeholder="请输入所属店铺ID" />
      </el-form-item>
      <el-form-item label="国家" prop="country">
        <el-input v-model="form.country" placeholder="请输入国家" />
      </el-form-item>
      <el-form-item label="币种" prop="currency">
        <el-input v-model="form.currency" placeholder="请输入币种" />
      </el-form-item>
      <el-form-item label="VAT税率" prop="vatRate">
        <el-input v-model="form.vatRate" placeholder="请输入VAT税率" />
      </el-form-item>
      <el-form-item label="时区" prop="timezone">
        <el-input v-model="form.timezone" placeholder="请输入时区" />
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

<script setup name="Site">
import { listSite, getSite, addSite, updateSite, changeSiteStatus } from "@/api/noon/masterdata/site"
const { proxy } = getCurrentInstance()
const siteList = ref([])
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
    siteCode: null,
    storeId: null,
    status: null,
  },
  rules: {
    siteCode: [{ required: true, message: '站点编码不能为空', trigger: 'blur' }],
    storeId: [{ required: true, message: '所属店铺ID不能为空', trigger: 'blur' }],
    currency: [{ required: true, message: '币种不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'blur' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listSite(params).then(res => {
    const payload = res.data || {}
    siteList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    siteCode: null,
    storeId: null,
    country: null,
    currency: null,
    vatRate: null,
    timezone: null,
    status: null,
  }
  proxy.resetForm("siteRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加站点" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getSite(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改站点" })
}
function submitForm() {
  proxy.$refs["siteRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateSite(form.value.id, form.value) : addSite(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changeSiteStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changeSiteStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changeSiteStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
