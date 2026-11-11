<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="fp-404">
    <p class="fp-404__code">404</p>
    <h1 class="fp-404__title">页面不存在</h1>
    <p class="fp-404__desc">你访问的地址可能已被移动或删除。</p>
    <el-button type="primary" @click="router.push({ name: 'dashboard' })">返回概览</el-button>
  </div>
</template>

<style scoped>
.fp-404 {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  min-height: 100vh;
  padding: var(--sp-6);
  text-align: center;
}

.fp-404__code {
  font-family: var(--font-mono);
  font-size: 56px;
  font-weight: 700;
  color: var(--fp-accent);
  line-height: 1;
}

.fp-404__title {
  font-size: 20px;
  font-weight: 650;
}

.fp-404__desc {
  margin-bottom: var(--sp-4);
  font-size: 13.5px;
  color: var(--fp-text-muted);
}
</style>
