<script setup lang="ts">
defineProps<{
  message: string
  title?: string
}>()

defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="fp-error" role="alert">
    <div class="fp-error__icon" aria-hidden="true">!</div>
    <div class="fp-grow">
      <p v-if="title" class="fp-error__title">{{ title }}</p>
      <p class="fp-error__msg">{{ message }}</p>
    </div>
    <el-button size="small" plain @click="$emit('retry')">重试</el-button>
  </div>
</template>

<style scoped>
.fp-error {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--fp-danger);
  border-radius: var(--fp-radius);
  background: var(--fp-danger-soft);
}

.fp-error__icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--fp-danger);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.fp-error__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fp-danger);
}

.fp-error__msg {
  font-size: 13px;
  color: var(--fp-text-muted);
}
</style>
