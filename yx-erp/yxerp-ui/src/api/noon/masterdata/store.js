import request from '@/utils/request'

const BASE = '/noon/masterdata/store'

// 查询店铺列表（授权站点内）
export function listStore(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询店铺详细
export function getStore(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增店铺（仅超管）
export function addStore(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改店铺（仅超管）
export function updateStore(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用店铺（仅超管）
export function changeStoreStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}
