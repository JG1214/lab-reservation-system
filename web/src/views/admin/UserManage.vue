<template>
  <div class="manage-page page-container-wide">
    <!-- 页面标题栏 -->
    <div class="page-head">
      <div class="head-left">
        <h2 class="page-title">用户管理</h2>
        <span class="page-tip">账号查询、新建用户、角色变更、启用/禁用与密码重置</span>
      </div>
      <div class="head-actions">
        <el-button type="primary" :icon="Plus" @click="handleOpenCreate">新建用户</el-button>
      </div>
    </div>
    <!-- 搜索栏 -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="账号 / 姓名 / 学号"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="query.role" placeholder="全部" clearable style="width: 130px">
            <el-option label="学生" :value="0" />
            <el-option label="教师" :value="2" />
            <el-option label="管理员" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 用户表格 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="70" align="right" />
        <el-table-column prop="username" label="账号" min-width="110" show-overflow-tooltip />
        <el-table-column prop="name" label="姓名" min-width="90" show-overflow-tooltip />
        <el-table-column prop="studentNo" label="学号" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.studentNo || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="90">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" size="small">{{ roleText(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.id === currentUserId">
              <el-tooltip content="不能操作当前登录的管理员账号" placement="top">
                <el-button size="small" disabled>改角色</el-button>
              </el-tooltip>
              <el-button size="small" disabled>禁用/启用</el-button>
              <el-button size="small" disabled>重置密码</el-button>
            </template>
            <template v-else>
              <el-button size="small" plain @click="handleChangeRole(row)">改角色</el-button>
              <el-button
                size="small"
                :type="row.status === 1 ? 'danger' : 'success'"
                plain
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button size="small" type="primary" plain @click="handleResetPassword(row)">
                重置密码
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-if="!loading && !records.length" description="没有找到符合条件的用户">
        <el-button type="primary" @click="handleReset">重置筛选</el-button>
      </el-empty>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>

    <!-- 角色变更弹窗（需求文档 V4.0 1.3：管理员端用户管理支持角色变更） -->
    <el-dialog v-model="roleDialogVisible" title="变更角色" width="440px">
      <div class="role-dialog">
        <p class="role-dialog-user">{{ roleForm.name }}（{{ roleForm.username }}）</p>
        <el-radio-group v-model="roleForm.role">
          <el-radio :value="0">学生</el-radio>
          <el-radio :value="2">教师</el-radio>
          <el-radio :value="1">管理员</el-radio>
        </el-radio-group>
        <p class="role-dialog-tip">
          学生可预约实验室并提交申请；教师可查看实验室占用总览并提交申请；管理员负责预约审核与资源维护。
        </p>
      </div>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="handleSubmitRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建用户弹窗（需求文档 V4.0 1.2：教师等账号由管理员创建） -->
    <el-dialog v-model="createDialogVisible" title="新建用户" width="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="createForm.username" maxlength="32" placeholder="登录时使用，最长 32 位" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="name">
          <el-input v-model="createForm.name" maxlength="20" placeholder="用于审核与记录展示" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="createForm.role">
            <el-radio :value="2">教师</el-radio>
            <el-radio :value="0">学生</el-radio>
            <el-radio :value="1">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="createForm.role === 0" label="学号" prop="studentNo">
          <el-input v-model="createForm.studentNo" maxlength="20" placeholder="学生角色必填" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input
            v-model="createForm.password"
            maxlength="20"
            show-password
            placeholder="不填则使用默认密码 123456"
          />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" maxlength="11" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" maxlength="50" placeholder="选填" />
        </el-form-item>
      </el-form>
      <p class="create-dialog-tip">
        教师可查看实验室占用总览并提交预约；学生可预约并提交申请；管理员负责审核与资源维护。
      </p>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleSubmitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { pageUsers, updateUserStatus, resetUserPassword, updateUserRole, createUser } from '@/api/user'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const currentUserId = userStore.userInfo ? userStore.userInfo.id : null

const loading = ref(false)
const records = ref([])
const total = ref(0)

/** 角色变更弹窗状态 */
const roleDialogVisible = ref(false)
const roleSubmitting = ref(false)
const roleForm = reactive({ id: null, username: '', name: '', role: null })

/** 新建用户弹窗状态 */
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  username: '',
  name: '',
  role: 2,
  studentNo: '',
  password: '',
  phone: '',
  email: ''
})

/** 选填项校验：留空即通过，填了才校验格式（与后端规则保持一致） */
function validateOptionalPassword(rule, value, callback) {
  if (!value) return callback()
  return value.length >= 6 ? callback() : callback(new Error('初始密码至少 6 位'))
}

function validateOptionalPhone(rule, value, callback) {
  if (!value) return callback()
  return /^1\d{10}$/.test(value) ? callback() : callback(new Error('请输入 11 位手机号'))
}

function validateOptionalEmail(rule, value, callback) {
  if (!value) return callback()
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value) ? callback() : callback(new Error('邮箱格式不正确'))
}

const createRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  studentNo: [{ required: true, message: '学生角色必须填写学号', trigger: 'blur' }],
  password: [{ validator: validateOptionalPassword, trigger: 'blur' }],
  phone: [{ validator: validateOptionalPhone, trigger: 'blur' }],
  email: [{ validator: validateOptionalEmail, trigger: 'blur' }]
}

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  role: null,
  status: null
})

/** 加载用户列表 */
async function loadData() {
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      role: query.role === null || query.role === '' ? undefined : query.role,
      status: query.status === null || query.status === '' ? undefined : query.status
    }
    const res = await pageUsers(params)
    records.value = res.data.records
    total.value = res.data.total
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 查询（重置页码后加载） */
function handleSearch() {
  query.page = 1
  loadData()
}

/** 重置筛选条件 */
function handleReset() {
  query.keyword = ''
  query.role = null
  query.status = null
  query.page = 1
  loadData()
}

/** 启用/禁用（二次确认） */
async function handleToggleStatus(row) {
  const action = row.status === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}用户「${row.name}（${row.username}）」吗？${action === '禁用' ? '禁用后该用户将无法登录。' : ''}`,
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await updateUserStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(`${action}成功`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 重置密码（二次确认） */
async function handleResetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `确定要将用户「${row.name}（${row.username}）」的密码重置为默认密码 123456 吗？`,
      '重置密码确认',
      { confirmButtonText: '确定重置', cancelButtonText: '取消', type: 'warning' }
    )
    await resetUserPassword(row.id)
    ElMessage.success('密码已重置为默认密码 123456')
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 角色文案（0-学生 / 1-管理员 / 2-教师） */
function roleText(role) {
  if (role === 1) return '管理员'
  if (role === 2) return '教师'
  return '学生'
}

/** 角色标签色：学生主色、教师成功色、管理员警告色 */
function roleTagType(role) {
  if (role === 1) return 'warning'
  if (role === 2) return 'success'
  return 'primary'
}

/** 打开角色变更弹窗（回填当前角色） */
function handleChangeRole(row) {
  roleForm.id = row.id
  roleForm.username = row.username
  roleForm.name = row.name
  roleForm.role = row.role
  roleDialogVisible.value = true
}

/** 提交角色变更 */
async function handleSubmitRole() {
  if (roleForm.role === null || roleForm.role === undefined) {
    ElMessage.warning('请选择角色')
    return
  }
  roleSubmitting.value = true
  try {
    await updateUserRole(roleForm.id, roleForm.role)
    ElMessage.success('角色已更新')
    roleDialogVisible.value = false
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  } finally {
    roleSubmitting.value = false
  }
}

/** 打开新建用户弹窗（每次重置表单，默认角色为教师） */
function handleOpenCreate() {
  Object.assign(createForm, {
    username: '',
    name: '',
    role: 2,
    studentNo: '',
    password: '',
    phone: '',
    email: ''
  })
  createDialogVisible.value = true
  nextTick(() => {
    if (createFormRef.value) createFormRef.value.clearValidate()
  })
}

/** 提交新建用户（留空的选填项不提交，由后端使用默认值） */
async function handleSubmitCreate() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch {
    return
  }
  createSubmitting.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      name: createForm.name.trim(),
      role: createForm.role,
      studentNo: createForm.role === 0 ? createForm.studentNo.trim() : undefined,
      password: createForm.password || undefined,
      phone: createForm.phone.trim() || undefined,
      email: createForm.email.trim() || undefined
    })
    ElMessage.success('用户已创建')
    createDialogVisible.value = false
    query.page = 1
    loadData()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    createSubmitting.value = false
  }
}

/** 时间格式化 */
function formatTime(time) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(loadData)
</script>

<style scoped>
/* 限宽 + 页头/搜索/表格三段统一 12px 节奏 */
.manage-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px;
}

/* 操作列三个按钮：默认 12px 间距在 220px 列宽内会折行，收到 6px */
:deep(.el-table .el-button + .el-button) {
  margin-left: 6px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.role-dialog-user {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--text-primary);
}

.role-dialog-tip {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

.create-dialog-tip {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

/* 窄屏：操作列按钮间距再收一档，保证 220px 列宽下三键同行 */
@media (max-width: 1200px) {
  :deep(.el-table .el-button + .el-button) {
    margin-left: 4px;
  }
}
</style>
