import request from '@/utils/request'

/** 待登记列表：已通过且使用日期已过、尚未登记的预约 */
export function listPendingUsage(params) {
  return request.get('/usage/pending', { params })
}

/** 使用登记：登记实际使用人数与使用状态 */
export function registerUsage(data) {
  return request.post('/usage', data)
}

/** 批量标记失约 */
export function batchNoShow(reservationIds, remark) {
  return request.post('/usage/batch-noshow', { reservationIds, remark })
}

/** 使用记录查询（日期区间 / 使用状态 / 实验室） */
export function listUsageRecords(params) {
  return request.get('/usage/manage', { params })
}

/** 使用记录导出（xlsx 文件流，需带 Token） */
export function exportUsageRecords(params) {
  return request.get('/usage/export', { params, responseType: 'blob' })
}
