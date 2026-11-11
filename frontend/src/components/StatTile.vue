<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    /** 辅助说明 */
    hint?: string
    /** 数值色调 */
    tone?: 'default' | 'accent' | 'success' | 'warning' | 'danger'
    /** 左侧强调条 */
    accent?: boolean
  }>(),
  { hint: '', tone: 'default', accent: false }
)
</script>

<template>
  <div class="fp-stat" :class="[`fp-stat--${tone}`, { 'fp-stat--accent': accent }]">
    <div class="fp-label">{{ label }}</div>
    <div class="fp-metric">{{ value }}</div>
    <div v-if="hint" class="fp-stat__hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.fp-stat {
  position: relative;
  padding: var(--sp-4) var(--sp-5);
  background: var(--fp-surface);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-radius);
  box-shadow: var(--fp-shadow-1);
  overflow: hidden;
}

.fp-stat::after {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--fp-accent);
  opacity: 0;
  transition: opacity 0.2s var(--ease);
}

.fp-stat--accent::after {
  opacity: 1;
}

.fp-stat .fp-metric {
  margin-top: 6px;
}

.fp-stat--accent .fp-metric {
  color: var(--fp-accent);
}

.fp-stat--success .fp-metric {
  color: var(--fp-success);
}

.fp-stat--warning .fp-metric {
  color: var(--fp-warning);
}

.fp-stat--danger .fp-metric {
  color: var(--fp-danger);
}

.fp-stat__hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--fp-text-subtle);
}
</style>
