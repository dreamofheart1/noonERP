import request from '@/utils/request'

const BASE = '/noon/masterdata/warehouse'

// 查询仓库列表（授权站点内）
export function listWarehouse(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询仓库详细
export function getWarehouse(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增仓库（仅超管）
export function addWarehouse(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改仓库（仅超管）
export function updateWarehouse(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用仓库（仅超管）
export function changeWarehouseStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}
