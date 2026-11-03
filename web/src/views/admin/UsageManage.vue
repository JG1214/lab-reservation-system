<template>
  <div class="usage-manage page-container-wide">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <div>
            <h2 class="panel-title">待登记</h2>
            <p class="panel-desc">已通过且使用日期已过的预约，需要在结束后登记实际使用人数</p>
          </div>
          <div class="panel-actions">
            <el-date-picker
              v-model="pendingDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="按使用日期筛选"
              clearable
              @change="loadPending"
            />
            <el-button :loading="pendingLoading" @click="loadPending">刷新</el-button>
            <el-button
              type="warning"
              plain
              :disabled="selectedRows.length === 0"
              @click="handleBatchNoShow"
            >
              批量标记失约{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="pendingLoading"
        :data="pendingList"
        row-key="reservationId"
        empty-text="暂无待登记的预约"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="useDate" label="使用日期" width="112" />
        <el-table-column label="时段" width="126">
          <template #default="{ row }">{{ clock(row.startTime) }}–{{ clock(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="实验室" min-width="180">
          <template #default="{ row }">
            {{ row.building }} {{ row.roomNo }}
            <span class="muted">{{ row.classroomName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="使用人" width="140">
          <template #default="{ row }">
            {{ row.realName || '-' }}
            <span class="muted">{{ row.username }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="peopleCount" label="预约人数" width="96" align="right" />
        <el-table-column prop="purpose" label="预约用途" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRegister(row)">登记</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <div>
            <h2 class="panel-title">使用记录</h2>
            <p class="panel-desc">按登记结果查询，可作为失约与使用率统计的原始依据</p>
          </div>
          <div class="panel-actions">
            <el-date-picker
              v-model="recordRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              range-separator="至"
            />
            <el-select v-model="query.useStatus" placeholder="使用状态" clearable class="status-select">
              <el-option
                v-for="item in USE_STATUS_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select v-model="query.classroomId" placeholder="全部实验室" clearable filterable class="room-select">
              <el-option
                v-for="room in classrooms"
                :key="room.id"
                :label="`${room.building} ${room.roomNo} ${room.name}`"
                :value="room.id"
              />
            </el-select>
            <el-button @click="handleSearch">查询</el-button>
            <el-button :loading="exporting" @click="handleExport">导出</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="recordLoading" :data="recordList" empty-text="暂无使用记录">
        <el-table-column prop="useDate" label="使用日期" width="112" />
        <el-table-column label="时段" width="126">
          <template #default="{ row }">{{ clock(row.startTime) }}–{{ clock(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="实验室" min-width="180">
          <template #default="{ row }">
            {{ row.building }} {{ row.roomNo }}
            <span class="muted">{{ row.classroomName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="使用人" width="140">
          <template #default="{ row }">
            {{ row.realName || '-' }}
            <span class="muted">{{ row.username }}</span>
          </template>
        </el-table-column>
        <el-table-column label="人数（实到/预约）" width="140" align="right">
          <template #default="{ row }">
            <span :class="{ 'people-gap': row.actualPeople !== row.peopleCount }">
              {{ row.actualPeople }} / {{ row.peopleCount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="使用状态" width="112">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.useStatus)" effect="plain" size="small">
              {{ row.useStatusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checkRemark" label="登记备注" min-width="160" show-overflow-tooltip />
        <el-table-column prop="registrarName" label="登记人" width="110" />
        <el-table-column label="登记时间" width="152">
          <template #default="{ row }">{{ formatDateTime(row.checkTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="登记使用情况" width="480px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="预约">
          <span class="dialog-text">
            {{ current?.building }} {{ current?.roomNo }} {{ current?.classroomName }}
          </span>
        </el-form-item>
        <el-form-item label="使用时段">
          <span class="dialog-text">
            {{ current?.useDate }} {{ clock(current?.startTime) }}–{{ clock(current?.endTime) }}
          </span>
        </el-form-item>
        <el-form-item label="预约人数">
          <span class="dialog-text">{{ current?.peopleCount }} 人</span>
        </el-form-item>
        <el-form-item label="使用状态" prop="useStatus">
          <el-radio-group v-model="form.useStatus" @change="handleStatusChange">
            <el-radio :value="1">正常使用</el-radio>
            <el-radio :value="2">部分使用</el-radio>
            <el-radio :value="3">失约</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="实际人数" prop="actualPeople">
          <el-input-number
            v-model="form.actualPeople"
            :min="0"
            :max="current?.peopleCount || 1"
            :disabled="form.useStatus === 3"
            style="width: 100%"
          />
          <p class="form-hint">{{ actualPeopleHint }}</p>
        </el-form-item>
        <el-form-item label="登记备注" prop="checkRemark">
          <el-input
            v-model="form.checkRemark"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="选填，例如：设备调试占用部分座位、学生集体请假"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchNoShow, exportUsageRecords, listPendingUsage, listUsageRecords, registerUsage } from '@/api/usage'
import { listClassrooms } from '@/api/classroom'

const USE_STATUS_OPTIONS = [
  { value: 1, label: '正常使用' },
  { value: 2, label: '部分使用' },
  { value: 3, label: '失约' }
]

const pendingLoading = ref(false)
const pendingList = ref([])
const pendingDate = ref('')
const selectedRows = ref([])

const recordLoading = ref(false)
const recordList = ref([])
const recordRange = ref(null)
const exporting = ref(false)
const query = reactive({ useStatus: undefined, classroomId: undefined })

const classrooms = ref([])

const dialogVisible = ref(false)
const submitting = ref(false)
const current = ref(null)
const formRef = ref(null)
const form = reactive({ useStatus: 1, actualPeople: 0, checkRemark: '' })

const rules = {
  useStatus: [{ required: true, message: '请选择使用状态', trigger: 'change' }],
  actualPeople: [{ required: true, message: '请填写实际使用人数', trigger: 'blur' }]
}

/** 实际人数提示：把校验规则提前讲清楚，避免提交后才报错 */
const actualPeopleHint = computed(() => {
  const total = current.value?.peopleCount || 0
  if (form.useStatus === 3) {
    return '标记失约表示完全未到场，实际人数固定为 0'
  }
  if (form.useStatus === 2) {
    return `部分使用需填写 1 ~ ${Math.max(total - 1, 1)} 人（小于预约人数 ${total} 人）`
  }
  return `正常使用请填写实际到场人数，最多 ${total} 人`
})

async function loadPending() {
  pendingLoading.value = true
  try {
    const res = await listPendingUsage({ date: pendingDate.value || undefined })
    pendingList.value = res.data || []
  } catch {
    pendingList.value = []
  } finally {
    pendingLoading.value = false
  }
}

async function loadRecords() {
  recordLoading.value = true
  try {
    const [start, end] = recordRange.value || []
    const res = await listUsageRecords({
      startDate: start,
      endDate: end,
      useStatus: query.useStatus,
      classroomId: query.classroomId
    })
    recordList.value = res.data || []
  } catch {
    recordList.value = []
  } finally {
    recordLoading.value = false
  }
}

async function loadClassrooms() {
  try {
    const res = await listClassrooms({ page: 1, size: 200 })
    classrooms.value = res.data?.records || res.data?.list || []
  } catch {
    classrooms.value = []
  }
}

function handleSelectionChange(rows) {
  selectedRows.value = rows
}

/** 切换状态时同步实际人数：失约强制 0，正常使用默认回填预约人数 */
function handleStatusChange(value) {
  if (value === 3) {
    form.actualPeople = 0
  } else if (value === 1) {
    form.actualPeople = current.value?.peopleCount || 1
  } else if (form.actualPeople >= (current.value?.peopleCount || 1)) {
    form.actualPeople = Math.max(1, (current.value?.peopleCount || 2) - 1)
  }
}

function openRegister(row) {
  current.value = row
  form.useStatus = 1
  form.actualPeople = row.peopleCount || 1
  form.checkRemark = ''
  dialogVisible.value = true
}

function resetForm() {
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await registerUsage({
      reservationId: current.value.reservationId,
      useStatus: form.useStatus,
      actualPeople: form.useStatus === 3 ? 0 : form.actualPeople,
      checkRemark: form.checkRemark
    })
    ElMessage.success('登记成功')
    dialogVisible.value = false
    loadPending()
    loadRecords()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    submitting.value = false
  }
}

async function handleBatchNoShow() {
  const ids = selectedRows.value.map((row) => row.reservationId)
  try {
    await ElMessageBox.confirm(
      `确认将选中的 ${ids.length} 条预约标记为失约吗？标记后实际使用人数记为 0，预约本身仍保持「已通过」状态。`,
      '批量标记失约',
      { confirmButtonText: '确认标记', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const res = await batchNoShow(ids, '管理员批量标记失约')
    ElMessage.success(`已标记 ${res.data} 条失约记录`)
    loadPending()
    loadRecords()
  } catch {
    // 统一错误提示
  }
}

function handleSearch() {
  loadRecords()
}

async function handleExport() {
  exporting.value = true
  try {
    const [start, end] = recordRange.value || []
    const blob = await exportUsageRecords({
      startDate: start,
      endDate: end,
      useStatus: query.useStatus,
      classroomId: query.classroomId
    })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `使用记录_${dayjs().format('YYYYMMDDHHmmss')}.xlsx`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功，请查看下载文件')
  } catch {
    // 统一错误提示
  } finally {
    exporting.value = false
  }
}

/** 使用状态对应的标签色：失约用危险色、部分使用用警示色 */
function statusTagType(value) {
  if (value === 3) {
    return 'danger'
  }
  if (value === 2) {
    return 'warning'
  }
  return 'success'
}

function clock(value) {
  if (!value) {
    return '-'
  }
  const text = String(value)
  return text.length > 5 ? text.slice(0, 5) : text
}

function formatDateTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(() => {
  loadPending()
  loadRecords()
  loadClassrooms()
})
</script>

<style scoped>
/* 限宽 + 两张卡片统一 12px 节奏（与其余管理端表格页一致） */
.usage-manage {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel {
  border-radius: var(--radius-lg);
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.panel-title {
  margin: 0;
  font-family: var(--font-display, inherit);
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.panel-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.status-select {
  width: 132px;
}

.room-select {
  width: 220px;
}

.muted {
  margin-left: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.people-gap {
  color: var(--el-color-warning-dark-2);
  font-weight: 600;
}

.dialog-text {
  color: var(--text-primary);
}

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

/* 窄屏：卡片头改成上下两段，筛选项整行铺满，不再被横向挤成一条 */
@media (max-width: 768px) {
  .panel-head {
    flex-direction: column;
    align-items: stretch;
  }
  .panel-actions {
    width: 100%;
  }
  .status-select,
  .room-select {
    width: 100%;
  }
}
</style>
