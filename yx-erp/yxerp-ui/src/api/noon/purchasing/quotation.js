import request from '@/utils/request'

const BASE = '/noon/purchasing/quotation'

// 查询报价列表（授权站点内）
export function listQuotation(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询指定日期的有效报价（供采购单带出单价）
export function getEffectiveQuotation(query) {
  return request({ url: BASE + '/effective', method: 'get', params: query })
}

// 查询报价详细
export function getQuotation(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增报价（仅超管，新 effectiveFrom 即保留历史价）
export function addQuotation(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改报价（仅超管）
export function updateQuotation(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}
