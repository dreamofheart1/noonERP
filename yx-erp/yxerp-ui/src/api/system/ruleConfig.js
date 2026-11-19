import request from '@/utils/request'

export function ruleHistory(params) {
  return request({ url: '/noon/rule-config/history', method: 'get', params })
}

export function createRule(data) {
  return request({ url: '/noon/rule-config', method: 'post', data })
}

export function revokeRule(id) {
  return request({ url: `/noon/rule-config/${id}/revoke`, method: 'post' })
}
