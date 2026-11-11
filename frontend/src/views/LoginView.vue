<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ]
}

async function submit(): Promise<void> {
  const instance = formRef.value
  if (!instance) return
  const valid = await instance.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.login({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="fp-auth">
    <div class="fp-auth__panel">
      <div class="fp-auth__brand">
        <div class="fp-auth__mark" aria-hidden="true">FP</div>
        <div>
          <h1 class="fp-auth__title">FlowPilot</h1>
          <p class="fp-auth__sub">智能工作流 · Agent 协同平台</p>
        </div>
      </div>

      <h2 class="fp-auth__heading">登录管理控制台</h2>
      <p class="fp-auth__hint">使用你的 FlowPilot 账号继续</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            :prefix-icon="User"
            placeholder="请输入用户名"
            autocomplete="username"
            clearable
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :prefix-icon="Lock"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="fp-auth__submit" :loading="submitting" @click="submit">
          登录
        </el-button>
      </el-form>

      <p class="fp-auth__footer">
        还没有账号？
        <router-link :to="{ name: 'register', query: route.query }">立即注册</router-link>
      </p>
    </div>

    <aside class="fp-auth__aside" aria-hidden="true">
      <div class="fp-auth__aside-inner">
        <h3 class="fp-auth__aside-title">把复杂流程交给 Agent</h3>
        <ul class="fp-auth__aside-list">
          <li>可视化编排 DAG，一键发布版本</li>
          <li>实时 SSE 追踪每个节点执行状态</li>
          <li>人工审批与报告产出闭环管理</li>
        </ul>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.fp-auth {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 100vh;
}

.fp-auth__panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  max-width: 420px;
  margin: 0 auto;
  padding: var(--sp-8) var(--sp-6);
}

.fp-auth__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-8);
}

.fp-auth__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: var(--fp-accent);
  color: var(--fp-on-accent);
  font-weight: 700;
  letter-spacing: 0.02em;
}

.fp-auth__title {
  font-size: 17px;
  font-weight: 650;
}

.fp-auth__sub {
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-auth__heading {
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.fp-auth__hint {
  margin: 6px 0 var(--sp-6);
  font-size: 13px;
  color: var(--fp-text-muted);
}

.fp-auth__submit {
  width: 100%;
  margin-top: var(--sp-2);
}

.fp-auth__footer {
  margin-top: var(--sp-6);
  font-size: 13px;
  color: var(--fp-text-muted);
}

.fp-auth__aside {
  position: relative;
  display: flex;
  align-items: flex-end;
  padding: var(--sp-8);
  background:
    radial-gradient(700px 320px at 80% 10%, rgba(76, 110, 245, 0.45), transparent 65%),
    linear-gradient(150deg, #1b2440 0%, #12162a 55%, #0d1020 100%);
  color: #eef1fb;
  overflow: hidden;
}

.fp-auth__aside::after {
  content: '';
  position: absolute;
  inset: auto -10% -30% -10%;
  height: 55%;
  background: radial-gradient(closest-side, rgba(116, 143, 252, 0.32), transparent);
}

.fp-auth__aside-inner {
  position: relative;
  z-index: 1;
  max-width: 420px;
}

.fp-auth__aside-title {
  color: #eef1fb;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.02em;
  line-height: 1.3;
}

.fp-auth__aside-list {
  margin: var(--sp-5) 0 0;
  padding-left: 18px;
  font-size: 14px;
  line-height: 2;
  color: rgba(238, 241, 251, 0.76);
}

@media (max-width: 900px) {
  .fp-auth {
    grid-template-columns: 1fr;
  }

  .fp-auth__aside {
    display: none;
  }

  .fp-auth__panel {
    max-width: 100%;
    padding: var(--sp-6) var(--sp-4);
  }
}
</style>
