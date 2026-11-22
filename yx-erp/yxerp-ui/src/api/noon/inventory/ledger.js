import request from '@/utils/request'

const BASE = '/noon/inventory/ledger'

// 四态台账只读（按站点授权过滤）
export function listLedger(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}
