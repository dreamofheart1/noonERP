import request from '@/utils/request'

const BASE = '/noon/masterdata/site'

// 查询站点列表（授权站点内）
export function listSite(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询站点详细
export function getSite(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增站点（仅超管）
export function addSite(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改站点（仅超管）
export function updateSite(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用站点（仅超管）
export function changeSiteStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}
