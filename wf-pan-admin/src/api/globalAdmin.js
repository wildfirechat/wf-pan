import request from '../utils/request'

export function getGlobalAdmins() {
  return request.get('/global-admins')
}

export function addGlobalAdmin(data) {
  return request.post('/global-admins', data)
}

export function removeGlobalAdmin(userId) {
  return request.delete(`/global-admins/${userId}`)
}
