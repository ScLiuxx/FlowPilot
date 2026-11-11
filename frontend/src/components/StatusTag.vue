<script setup lang="ts">
import { computed } from 'vue'
import type { StatusTone } from '@/utils/constants'

const props = defineProps<{
  label: string
  tone: StatusTone
  /** 运行态显示呼吸圆点 */
  pulse?: boolean
}>()

const toneClass = computed(() => `fp-status--${props.tone}`)
</script>

<template>
  <span class="fp-status" :class="toneClass">
    <span v-if="pulse" class="fp-status__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped>
.fp-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px;
  border-radius: var(--fp-pill);
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.fp-status__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: fp-pulse 1.6s ease-in-out infinite;
}

.fp-status--success {
  color: var(--fp-success);
  background: var(--fp-success-soft);
}

.fp-status--warning {
  color: var(--fp-warning);
  background: var(--fp-warning-soft);
}

.fp-status--danger {
  color: var(--fp-danger);
  background: var(--fp-danger-soft);
}

.fp-status--info {
  color: var(--fp-info);
  background: var(--fp-info-soft);
}

.fp-status--running {
  color: var(--fp-accent);
  background: var(--fp-accent-soft);
}

.fp-status--neutral {
  color: var(--fp-text-muted);
  background: var(--fp-neutral-soft);
}

@keyframes fp-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
</style>
