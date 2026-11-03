import request from '@/utils/request'

/**
 * 开放时段接口封装（需求设计文档 V4.0 规则 3）
 * - /api/slot/manage、/api/slot、/api/slot/batch 为管理员专属
 * - /api/slot/classroom/** 为登录即可的只读查询
 */

/* ==================== 管理端 ==================== */

/** 管理端：时段列表（可按实验室、状态筛选） */
export function listSlots(params) {
  return request.get('/slot/manage', { params })
}

/** 管理端：新增时段 */
export function createSlot(data) {
  return request.post('/slot', data)
}

/** 管理端：编辑时段 */
export function updateSlot(data) {
  return request.put(`/slot/${data.id}`, data)
}

/** 管理端：删除时段（存在未过期有效预约时后端返回 400，应改为停用） */
export function deleteSlot(id) {
  return request.delete(`/slot/${id}`)
}

/** 管理端：为多个实验室批量生成同一时段配置 */
export function batchCreateSlots(data) {
  return request.post('/slot/batch', data)
}

/* ==================== 学生端 / 教师端 ==================== */

/** 查询某实验室某日生效的开放时段（学生端教室详情页展示可用时间） */
export function listClassroomSlots(classroomId, date) {
  return request.get(`/slot/classroom/${classroomId}`, { params: { date } })
}
