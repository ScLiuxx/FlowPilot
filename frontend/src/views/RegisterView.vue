<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, Message, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3-32 个字符', trigger: 'blur' }
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== form.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

async function submit(): Promise<void> {
  const instance = formRef.value
  if (!instance) return
  const valid = await instance.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.register({
      username: form.username,
      password: form.password,
      email: form.email,
      nickname: form.nickname
    })
    ElMessage.success('注册成功，请登录')
    await router.replace({ name: 'login', query: route.query })
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="fp-reg">
    <div class="fp-reg__panel">
      <div class="fp-reg__brand">
        <div class="fp-reg__mark" aria-hidden="true">FP</div>
        <div>
          <h1 class="fp-reg__title">创建账号</h1>
          <p class="fp-reg__sub">注册后即可管理工作流与 Agent 协作</p>
        </div>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :prefix-icon="User" placeholder="登录使用的账号" clearable />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="展示名称" clearable />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" :prefix-icon="Message" placeholder="用于接收通知" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :prefix-icon="Lock"
            placeholder="至少 6 位"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            :prefix-icon="Lock"
            placeholder="再次输入密码"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="fp-reg__submit" :loading="submitting" @click="submit">
          注册
        </el-button>
      </el-form>

      <p class="fp-reg__footer">
        已有账号？
        <router-link :to="{ name: 'login', query: route.query }">返回登录</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.fp-reg {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--sp-6) var(--sp-4);
}

.fp-reg__panel {
  width: 100%;
  max-width: 440px;
  padding: var(--sp-7);
  background: var(--fp-surface);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-radius-lg);
  box-shadow: var(--fp-shadow-2);
}

.fp-reg__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-6);
}

.fp-reg__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: var(--fp-accent);
  color: var(--fp-on-accent);
  font-weight: 700;
}

.fp-reg__title {
  font-size: 17px;
  font-weight: 650;
}

.fp-reg__sub {
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-reg__submit {
  width: 100%;
  margin-top: var(--sp-2);
}

.fp-reg__footer {
  margin-top: var(--sp-5);
  font-size: 13px;
  color: var(--fp-text-muted);
}
</style>
