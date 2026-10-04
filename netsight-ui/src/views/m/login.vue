<template>
  <div class="m-login linear-theme">
    <div class="login-header">
      <div class="logo">慧眼运维</div>
      <div class="subtitle">维修人员工单反馈</div>
    </div>
    <div class="login-form">
      <van-cell-group inset class="light-group">
        <van-field
          v-model="phone"
          type="tel"
          label="手机号"
          placeholder="请输入手机号"
          maxlength="11"
        />
        <van-field
          v-model="code"
          type="tel"
          label="验证码"
          placeholder="请输入验证码"
          maxlength="6"
        >
          <template #button>
            <van-button size="small" class="lavender-text" :disabled="counting" @click="sendCode">
              {{ counting ? countdown + 's' : '获取验证码' }}
            </van-button>
          </template>
        </van-field>
      </van-cell-group>
      <div class="login-btn">
        <van-button block class="lavender-btn" :loading="loading" @click="login">登 录</van-button>
      </div>
    </div>
  </div>
</template>

<script>
import { Toast } from 'vant'
import request from '@/utils/request'
import { setToken, getToken } from '@/utils/auth'

export default {
  name: 'MLogin',
  data() {
    return {
      phone: '',
      code: '',
      counting: false,
      countdown: 60,
      loading: false
    }
  },
  created() {
    if (getToken()) {
      this.$router.replace('/m/orders')
    }
  },
  methods: {
    sendCode() {
      if (!/^1[3-9]\d{9}$/.test(this.phone)) {
        Toast('请输入正确的手机号')
        return
      }
      request.post('/auth/sms-code', null, { params: { phone: this.phone } }).then(() => {
        Toast('验证码已发送')
        this.counting = true
        this.countdown = 60
        this.timer = setInterval(() => {
          this.countdown--
          if (this.countdown <= 0) {
            clearInterval(this.timer)
            this.counting = false
          }
        }, 1000)
      })
    },
    login() {
      if (!/^1[3-9]\d{9}$/.test(this.phone)) {
        Toast('请输入正确的手机号')
        return
      }
      if (!this.code) {
        Toast('请输入验证码')
        return
      }
      this.loading = true
      request.post('/auth/login', {
        phone: this.phone,
        code: this.code,
        clientType: 'm'
      }).then(res => {
        this.loading = false
        setToken(res.data.token)
        localStorage.setItem('Admin-User', JSON.stringify(res.data.user))
        Toast('登录成功')
        this.$router.replace('/m/orders')
      }).catch(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped>
.linear-theme {
  /* Linear light tokens */
  --canvas: #f7f8f8;
  --surface: #ffffff;
  --hairline: #e6e7ea;
  --ink: #1f2937;
  --ink-muted: #6b7280;
  --ink-subtle: #9ca3af;
  --primary: #5e6ad2;
  --primary-hover: #828fff;

  min-height: 100vh;
  background: var(--canvas);
  padding: 80px 0 40px;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', system-ui, sans-serif;
}
.login-header {
  text-align: center;
  color: var(--ink);
  margin-bottom: 48px;
}
.logo {
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.6px;
  margin-bottom: 8px;
}
.subtitle {
  font-size: 14px;
  color: var(--ink-subtle);
}
.login-form {
  padding: 0 16px;
}
.login-btn {
  margin-top: 32px;
  padding: 0 16px;
}

/* Light cell group */
::v-deep .light-group {
  background: var(--surface);
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--hairline);
}
::v-deep .light-group .van-cell {
  background: var(--surface);
}
::v-deep .light-group .van-field__label {
  color: var(--ink);
}
::v-deep .light-group .van-field__control {
  color: var(--ink);
}

/* Lavender buttons */
::v-deep .lavender-text {
  background: transparent;
  color: var(--primary);
  border: none;
  font-weight: 500;
}
::v-deep .lavender-btn {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  height: 48px;
  font-size: 16px;
  font-weight: 500;
}
::v-deep .lavender-btn:active {
  background: var(--primary-hover);
}
</style>
