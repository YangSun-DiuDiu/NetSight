import request from '@/utils/request'

// 手机号 + 短信验证码登录
export function login(phone, code, clientType) {
  const data = {
    phone,
    code,
    clientType
  }
  return request({
    url: '/auth/login',
    headers: {
      isToken: false,
      repeatSubmit: false
    },
    method: 'post',
    data: data
  })
}

// 发送短信验证码
export function sendSmsCode(phone) {
  return request({
    url: '/auth/sms-code',
    headers: {
      isToken: false
    },
    method: 'post',
    params: { phone }
  })
}

// 获取用户详细信息
export function getInfo() {
  return request({
    url: '/getInfo',
    method: 'get'
  })
}

// 退出方法
export function logout() {
  return request({
    url: '/auth/logout',
    method: 'post'
  })
}
