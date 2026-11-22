import request from '@/utils/request'

const BASE = '/noon/purchasing/order'

// 查询采购单列表（授权站点内）
export function listOrder(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询采购单详细（含 items 明细）
export function getOrder(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增采购单（仅超管，DRAFT）
export function addOrder(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改采购单（仅超管，仅 DRAFT 可改）
export function updateOrder(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 确认采购单（DRAFT -> CONFIRMED）
export function confirmOrder(id) {
  return request({ url: BASE + '/' + id + '/confirm', method: 'post' })
}

// 关单（RECEIVED -> CLOSED）
export function closeOrder(id) {
  return request({ url: BASE + '/' + id + '/close', method: 'post' })
}
