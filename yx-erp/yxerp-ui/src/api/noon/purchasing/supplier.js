import request from '@/utils/request'

const BASE = '/noon/purchasing/supplier'

// 查询供应商列表（授权站点内）
export function listSupplier(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询供应商详细
export function getSupplier(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增供应商（仅超管）
export function addSupplier(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改供应商（仅超管）
export function updateSupplier(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用供应商（仅超管）
export function changeSupplierStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}
