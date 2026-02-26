import request from '../utils/request'

export function getLogs(params) {
  return request.get('/logs', { params })
}

export function clearAllLogs() {
  return request.delete('/logs/clear')
}

export function clearLogsBefore(days) {
  return request.delete('/logs/clear-before', { params: { days } })
}
