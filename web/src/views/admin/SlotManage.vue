<template>
  <div class="manage-page page-container-wide">
    <div class="page-head">
      <div class="head-left">
        <h2 class="page-title">开放时段管理</h2>
        <span class="page-tip">配置实验室可预约的日期区间、星期与开放时间，未配置时段的实验室不可被预约</span>
      </div>
      <div class="head-actions">
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增时段</el-button>
      </div>
    </div>

    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="实验室">
          <el-select
            v-model="query.classroomId"
            placeholder="全部实验室"
            clearable
            filterable
            style="width: 220px"
          >
            <el-option
              v-for="room in roomOptions"
              :key="room.id"
              :label="`${room.name}（${room.roomNo}）`"
              :value="room.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用中" :value="1" />
            <el-option label="已停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="loadData">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button :icon="Files" @click="openBatchDialog">批量生成</el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="64" align="right" />
        <el-table-column label="实验室" min-width="180">
          <template #default="{ row }">
            <span class="room-name">{{ row.classroomName }}</span>
            <span class="room-no">{{ row.roomNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="生效日期" width="210">
          <template #default="{ row }">
            {{ formatDate(row.startDate) }} 至 {{ formatDate(row.endDate) }}
          </template>
        </el-table-column>
        <el-table-column prop="weekDaysLabel" label="开放星期" min-width="150" show-overflow-tooltip />
        <el-table-column label="开放时间" width="140">
          <template #default="{ row }">
            {{ formatClock(row.openTime) }}–{{ formatClock(row.closeTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="slotCapacity" label="人数上限" width="100" align="right">
          <template #default="{ row }">{{ row.slotCapacity }} 人</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openDialog(row)">编辑</el-button>
            <el-button
              size="small"
              :type="row.status === 1 ? 'warning' : 'success'"
              plain
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !records.length" description="还没有配置开放时段">
        <el-button type="primary" @click="openBatchDialog">为实验室批量生成时段</el-button>
      </el-empty>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑开放时段' : '新增开放时段'"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="96px">
        <el-form-item label="实验室" prop="classroomId">
          <el-select
            v-model="form.classroomId"
            placeholder="请选择实验室"
            filterable
            :disabled="!!form.id"
            style="width: 100%"
          >
            <el-option
              v-for="room in roomOptions"
              :key="room.id"
              :label="`${room.name}（${room.roomNo}，容量 ${room.capacity} 人）`"
              :value="room.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期" prop="dateRange">
          <el-date-picker
            v-model="form.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开放星期" prop="weekDays">
          <el-checkbox-group v-model="form.weekDays">
            <el-checkbox v-for="d in WEEK_OPTIONS" :key="d.value" :value="d.value">
              {{ d.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="开放时间" prop="timeRange">
          <el-time-picker
            v-model="form.timeRange"
            is-range
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            format="HH:mm"
            value-format="HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="人数上限" prop="slotCapacity">
          <el-input-number v-model="form.slotCapacity" :min="1" :max="10000" style="width: 100%" />
          <div class="form-hint">不得超过实验室容量（最多 {{ selectedRoomCapacity }} 人）</div>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" maxlength="255" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量生成 -->
    <el-dialog
      v-model="batchVisible"
      title="批量生成开放时段"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetBatchForm"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="所选实验室将生成完全相同的时段配置；与已有配置重叠的实验室会被跳过，不影响其余实验室"
        class="batch-alert"
      />
      <el-form ref="batchFormRef" :model="batchForm" :rules="batchRules" label-width="96px">
        <el-form-item label="实验室" prop="classroomIds">
          <el-select
            v-model="batchForm.classroomIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择实验室"
            style="width: 100%"
          >
            <el-option
              v-for="room in roomOptions"
              :key="room.id"
              :label="`${room.name}（${room.roomNo}）`"
              :value="room.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期" prop="dateRange">
          <el-date-picker
            v-model="batchForm.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开放星期" prop="weekDays">
          <el-checkbox-group v-model="batchForm.weekDays">
            <el-checkbox v-for="d in WEEK_OPTIONS" :key="d.value" :value="d.value">
              {{ d.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="开放时间" prop="timeRange">
          <el-time-picker
            v-model="batchForm.timeRange"
            is-range
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            format="HH:mm"
            value-format="HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="人数上限" prop="slotCapacity">
          <el-input-number v-model="batchForm.slotCapacity" :min="1" :max="10000" style="width: 100%" />
          <div class="form-hint">上限不得超过所选中实验室中容量最小者</div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="batchForm.remark" maxlength="255" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="handleBatchSubmit">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Files } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import {
  listSlots,
  createSlot,
  updateSlot,
  deleteSlot,
  batchCreateSlots
} from '@/api/slot'
import { pageClassrooms } from '@/api/classroom'

/** 星期选项（与后端 week_days 存储口径一致：1=周一 … 7=周日） */
const WEEK_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' }
]

const loading = ref(false)
const records = ref([])
const roomOptions = ref([])

const query = reactive({ classroomId: null, status: null })

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const emptyForm = {
  id: null,
  classroomId: null,
  dateRange: [],
  weekDays: [1, 2, 3, 4, 5],
  timeRange: ['08:00:00', '22:00:00'],
  slotCapacity: 30,
  status: 1,
  remark: ''
}
const form = reactive({ ...emptyForm })

const selectedRoomCapacity = computed(() => {
  const room = roomOptions.value.find((r) => r.id === form.classroomId)
  return room && room.capacity ? room.capacity : '—'
})

const formRules = {
  classroomId: [{ required: true, message: '请选择实验室', trigger: 'change' }],
  dateRange: [
    {
      validator: (_rule, value, callback) =>
        value && value.length === 2 ? callback() : callback(new Error('请选择生效日期区间')),
      trigger: 'change'
    }
  ],
  weekDays: [
    {
      validator: (_rule, value, callback) =>
        value && value.length ? callback() : callback(new Error('至少选择一个开放星期')),
      trigger: 'change'
    }
  ],
  timeRange: [
    {
      validator: (_rule, value, callback) => {
        if (!value || value.length !== 2) {
          return callback(new Error('请选择开放时间'))
        }
        if (value[0] >= value[1]) {
          return callback(new Error('开始时间必须早于结束时间'))
        }
        callback()
      },
      trigger: 'change'
    }
  ],
  slotCapacity: [{ required: true, message: '请输入人数上限', trigger: 'blur' }]
}

const batchVisible = ref(false)
const batchSubmitting = ref(false)
const batchFormRef = ref(null)
const emptyBatchForm = {
  classroomIds: [],
  dateRange: [],
  weekDays: [1, 2, 3, 4, 5],
  timeRange: ['08:00:00', '12:00:00'],
  slotCapacity: 30,
  remark: ''
}
const batchForm = reactive({ ...emptyBatchForm })

const batchRules = {
  classroomIds: [
    {
      validator: (_rule, value, callback) =>
        value && value.length ? callback() : callback(new Error('请至少选择一个实验室')),
      trigger: 'change'
    }
  ],
  dateRange: formRules.dateRange,
  weekDays: formRules.weekDays,
  timeRange: formRules.timeRange,
  slotCapacity: formRules.slotCapacity
}

/** 加载时段列表 + 实验室下拉数据 */
async function loadData() {
  loading.value = true
  try {
    const params = {
      classroomId: query.classroomId || undefined,
      status: query.status === null || query.status === '' ? undefined : query.status
    }
    const res = await listSlots(params)
    records.value = res.data || []
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 加载启用中的实验室（时段只能挂在启用实验室上） */
async function loadRooms() {
  try {
    const res = await pageClassrooms({ page: 1, size: 200, status: 1 })
    roomOptions.value = res.data.records || []
  } catch {
    // 统一错误提示已由 request.js 处理
  }
}

function handleReset() {
  query.classroomId = null
  query.status = null
  loadData()
}

function openDialog(row) {
  if (row) {
    Object.assign(form, emptyForm, {
      id: row.id,
      classroomId: row.classroomId,
      dateRange: [formatDate(row.startDate), formatDate(row.endDate)],
      weekDays: String(row.weekDays || '')
        .split(',')
        .filter(Boolean)
        .map(Number),
      timeRange: [formatClock(row.openTime), formatClock(row.closeTime)],
      slotCapacity: row.slotCapacity,
      status: row.status,
      remark: row.remark || ''
    })
  } else {
    Object.assign(form, emptyForm)
  }
  dialogVisible.value = true
}

function openBatchDialog() {
  Object.assign(batchForm, emptyBatchForm)
  batchVisible.value = true
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload = {
      classroomId: form.classroomId,
      startDate: form.dateRange[0],
      endDate: form.dateRange[1],
      weekDays: form.weekDays.join(','),
      openTime: form.timeRange[0],
      closeTime: form.timeRange[1],
      slotCapacity: form.slotCapacity,
      status: form.status,
      remark: form.remark
    }
    if (form.id) {
      await updateSlot({ ...payload, id: form.id })
      ElMessage.success('已保存')
    } else {
      await createSlot(payload)
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    loadData()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    submitting.value = false
  }
}

async function handleBatchSubmit() {
  try {
    await batchFormRef.value.validate()
  } catch {
    return
  }
  batchSubmitting.value = true
  try {
    const res = await batchCreateSlots({
      classroomIds: batchForm.classroomIds,
      startDate: batchForm.dateRange[0],
      endDate: batchForm.dateRange[1],
      weekDays: batchForm.weekDays.join(','),
      openTime: batchForm.timeRange[0],
      closeTime: batchForm.timeRange[1],
      slotCapacity: batchForm.slotCapacity,
      remark: batchForm.remark
    })
    const count = res.data || 0
    ElMessage.success(`已为 ${count} 间实验室生成时段`)
    batchVisible.value = false
    loadData()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    batchSubmitting.value = false
  }
}

function resetForm() {
  formRef.value && formRef.value.resetFields()
  Object.assign(form, emptyForm)
}

function resetBatchForm() {
  batchFormRef.value && batchFormRef.value.resetFields()
  Object.assign(batchForm, emptyBatchForm)
}

/** 启用/停用（停用后该时段不再对外可预约，但历史预约不受影响） */
async function handleToggleStatus(row) {
  const action = row.status === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}「${row.classroomName} ${formatClock(row.openTime)}–${formatClock(row.closeTime)}」这个时段吗？` +
        (row.status === 1 ? '停用后该时段的日期与时间将不再可被预约。' : ''),
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await updateSlot({ ...row, status: row.status === 1 ? 0 : 1 })
    ElMessage.success(`已${action}`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.classroomName} ${formatClock(row.openTime)}–${formatClock(row.closeTime)}」这个时段吗？` +
        '存在未过期预约时将无法删除，可改为停用。',
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteSlot(row.id)
    ElMessage.success('已删除')
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

function formatDate(value) {
  return value ? dayjs(value).format('YYYY-MM-DD') : '-'
}

function formatClock(value) {
  if (!value) {
    return '-'
  }
  return String(value).length > 5 ? String(value).slice(0, 5) : String(value)
}

onMounted(() => {
  loadRooms()
  loadData()
})
</script>

<style scoped>
/* 限宽 + 页头/搜索/表格三段统一 12px 节奏 */
.manage-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px;
}

.toolbar {
  margin-bottom: 12px;
}

.room-name {
  color: var(--text-primary);
}

.room-no {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

.batch-alert {
  margin-bottom: 16px;
}

/* 窄屏：批量生成按钮整行铺满，不再被表格宽度挤成半截 */
@media (max-width: 768px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
