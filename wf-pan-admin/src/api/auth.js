import request from '../utils/request'

export function login(data) {
  return request.post('/auth/login', data)
}

export function logout() {
  return request.post('/auth/logout')
}

export function getUserInfo() {
  return request.get('/auth/info')
}

export function changePassword(data) {
  return request.post('/auth/change-password', data)
}
