<template>
  <div class="login">
    <!-- 背景动效：云边协同网络拓扑（V1.2 工业蓝白风） -->
    <div class="login-bg" aria-hidden="true">
      <svg viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <radialGradient id="glow" cx="50%" cy="42%" r="65%">
            <stop offset="0%" stop-color="#1B4DD8" stop-opacity="0.35"/>
            <stop offset="100%" stop-color="#041445" stop-opacity="0"/>
          </radialGradient>
          <linearGradient id="lineGrad" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#2E6AF5"/>
            <stop offset="100%" stop-color="#7CA0EE"/>
          </linearGradient>
        </defs>
        <rect width="1440" height="900" fill="url(#glow)"/>
        <!-- 细网格线 -->
        <g stroke="rgba(124,160,238,0.07)" stroke-width="1">
          <path d="M0 150 H1440 M0 300 H1440 M0 450 H1440 M0 600 H1440 M0 750 H1440"/>
          <path d="M180 0 V900 M360 0 V900 M540 0 V900 M720 0 V900 M900 0 V900 M1080 0 V900 M1260 0 V900"/>
        </g>
        <!-- 云朵（右上） -->
        <g class="bg-cloud">
          <ellipse cx="1180" cy="170" rx="120" ry="42" fill="rgba(255,255,255,0.06)"/>
          <ellipse cx="1120" cy="150" rx="60" ry="32" fill="rgba(255,255,255,0.07)"/>
          <ellipse cx="1240" cy="152" rx="52" ry="28" fill="rgba(255,255,255,0.06)"/>
        </g>
        <!-- 网络节点连线（左-中） -->
        <g stroke="#4C7EF0" stroke-width="1.4" opacity="0.55">
          <path d="M120 720 L300 580 L520 640 L760 520"/>
          <path d="M300 580 L340 420"/>
          <path d="M520 640 L560 480"/>
          <path d="M760 520 L820 380"/>
        </g>
        <g class="bg-node" fill="#4C7EF0">
          <circle cx="120" cy="720" r="5"/>
          <circle cx="300" cy="580" r="6"/>
          <circle cx="520" cy="640" r="5"/>
          <circle cx="760" cy="520" r="6"/>
          <circle cx="340" cy="420" r="4"/>
          <circle cx="560" cy="480" r="4"/>
          <circle cx="820" cy="380" r="4"/>
        </g>
        <!-- 信号波纹（左下） -->
        <g class="bg-wave" fill="none" stroke="#2E6AF5" stroke-width="1.4">
          <circle cx="200" cy="760" r="30"/>
          <circle cx="200" cy="760" r="60"/>
          <circle cx="200" cy="760" r="95"/>
        </g>
        <!-- 三色状态点 -->
        <g>
          <circle cx="980" cy="640" r="5" fill="#EF4444" class="bg-state"/>
          <circle cx="1030" cy="620" r="5" fill="#10B981" class="bg-state"/>
          <circle cx="1080" cy="650" r="5" fill="#909399" class="bg-state"/>
        </g>
      </svg>
    </div>

    <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form">
      <div class="login-logo">
        <img src="@/assets/logo/logo-a-eye.png" alt="NetSight" />
      </div>
      <h3 class="title">{{title}}</h3>
      <el-form-item prop="phone">
        <el-input
          v-model="loginForm.phone"
          type="text"
          auto-complete="off"
          placeholder="手机号"
          maxlength="11"
        >
          <svg-icon slot="prefix" icon-class="phone" class="el-input__icon input-icon" />
        </el-input>
      </el-form-item>
      <el-form-item prop="code">
        <el-input
          v-model="loginForm.code"
          auto-complete="off"
          placeholder="短信验证码"
          style="width: 63%"
          maxlength="6"
          @keyup.enter.native="handleLogin"
        >
          <svg-icon slot="prefix" icon-class="validCode" class="el-input__icon input-icon" />
        </el-input>
        <div class="login-code">
          <el-button
            type="primary"
            plain
            size="small"
            :disabled="codeBtnDisabled"
            @click="handleSendCode"
          >
            {{ codeBtnText }}
          </el-button>
        </div>
      </el-form-item>
      <el-form-item style="width:100%;">
        <el-button
          :loading="loading"
          size="medium"
          type="primary"
          style="width:100%;"
          @click.native.prevent="handleLogin"
        >
          <span v-if="!loading">登 录</span>
          <span v-else>登 录 中...</span>
        </el-button>
      </el-form-item>
      <div class="login-tip" v-if="showDevHint">开发阶段验证码固定为 123456（模拟发送，不实际下发短信）</div>
    </el-form>
    <!--  底部  -->
    <div class="el-login-footer">
      <span>{{ footerContent }}</span>
    </div>
  </div>
</template>

<script>
import { sendSmsCode } from "@/api/login"
import defaultSettings from '@/settings'

export default {
  name: "Login",
  data() {
    return {
      title: process.env.VUE_APP_TITLE,
      footerContent: defaultSettings.footerContent,
      // 开发阶段提示（固定验证码 123456）按环境显隐：仅开发/测试构建显示，生产不渲染
      showDevHint: process.env.VUE_APP_SHOW_DEV_HINT === 'true',
      loginForm: {
        phone: "",
        code: "",
        clientType: "pc"
      },
      loginRules: {
        phone: [
          { required: true, trigger: "blur", message: "请输入手机号" },
          { pattern: /^1[3-9]\d{9}$/, trigger: "blur", message: "手机号格式不正确" }
        ],
        code: [
          { required: true, trigger: "blur", message: "请输入验证码" },
          { pattern: /^\d{6}$/, trigger: "blur", message: "验证码为6位数字" }
        ]
      },
      loading: false,
      codeBtnDisabled: false,
      codeBtnText: "获取验证码",
      countdown: 0,
      redirect: undefined
    }
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  methods: {
    // 发送短信验证码（开发/测试阶段后端模拟，固定 123456）
    handleSendCode() {
      this.$refs.loginForm.validateField("phone", error => {
        if (error) return
        sendSmsCode(this.loginForm.phone).then(() => {
          this.$message.success(this.showDevHint ? "验证码已发送（开发模式固定 123456）" : "验证码已发送，请注意查收")
          this.startCountdown()
        }).catch(() => {})
      })
    },
    // 60s 倒计时
    startCountdown() {
      this.codeBtnDisabled = true
      this.countdown = 60
      this.codeBtnText = `${this.countdown}s 后重新获取`
      const timer = setInterval(() => {
        this.countdown--
        this.codeBtnText = `${this.countdown}s 后重新获取`
        if (this.countdown <= 0) {
          clearInterval(timer)
          this.codeBtnDisabled = false
          this.codeBtnText = "获取验证码"
        }
      }, 1000)
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true
          this.$store.dispatch("Login", this.loginForm).then(() => {
            this.$router.push({ path: this.redirect || "/" }).catch(()=>{})
          }).catch(() => {
            this.loading = false
          })
        }
      })
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background: linear-gradient(160deg, #041445 0%, #0B2A6B 60%, #12377E 100%);
  background-size: cover;
  position: relative;
  overflow: hidden;
}

.login-bg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  pointer-events: none;

  svg {
    width: 100%;
    height: 100%;
  }

  .bg-node {
    animation: nodeBreath 3.2s ease-in-out infinite;
  }

  .bg-wave circle {
    transform-origin: 200px 760px;
    animation: waveExpand 3.5s ease-out infinite;
  }

  .bg-wave circle:nth-child(2) { animation-delay: 0.6s; }
  .bg-wave circle:nth-child(3) { animation-delay: 1.2s; }

  .bg-cloud {
    animation: cloudDrift 26s linear infinite alternate;
  }

  .bg-state {
    animation: stateBreath 4s ease-in-out infinite;
  }
  .bg-state:nth-child(2) { animation-delay: 0.8s; }
  .bg-state:nth-child(3) { animation-delay: 1.6s; }
}

@keyframes nodeBreath {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 1; }
}

@keyframes waveExpand {
  0% { opacity: 0.8; transform: scale(0.6); }
  70% { opacity: 0.05; }
  100% { opacity: 0; transform: scale(1.4); }
}

@keyframes cloudDrift {
  0% { transform: translateX(0); }
  100% { transform: translateX(-36px); }
}

@keyframes stateBreath {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}

.login-logo {
  text-align: center;
  margin-bottom: 14px;

  img {
    width: 64px;
    height: 64px;
  }
}

.title {
  margin: 0px auto 28px auto;
  text-align: center;
  color: #0B2A6B;
  font-weight: 600;
  font-size: 20px;
}

.login-form {
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 12px 48px rgba(4, 20, 69, 0.45);
  width: 400px;
  padding: 30px 25px 5px 25px;
  z-index: 1;
  .el-input {
    height: 38px;
    input {
      height: 38px;
    }
  }
  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 2px;
  }
}
.login-tip {
  font-size: 13px;
  text-align: center;
  color: #bfbfbf;
}
.login-code {
  width: 33%;
  height: 38px;
  float: right;
  text-align: right;
}
.el-login-footer {
  height: 40px;
  line-height: 40px;
  position: fixed;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: rgba(255, 255, 255, 0.75);
  font-family: Arial;
  font-size: 12px;
  letter-spacing: 1px;
}
</style>
