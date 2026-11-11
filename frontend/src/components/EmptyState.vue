<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
    /** 图标字符或短标签 */
    icon?: string
  }>(),
  { description: '', icon: '∅' }
)
</script>

<template>
  <div class="fp-empty">
    <div class="fp-empty__icon" aria-hidden="true">{{ icon }}</div>
    <p class="fp-empty__title">{{ title }}</p>
    <p v-if="description" class="fp-empty__desc">{{ description }}</p>
    <div class="fp-empty__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.fp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: var(--sp-8) var(--sp-4);
  text-align: center;
}

.fp-empty__icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin-bottom: var(--sp-1);
  border-radius: 50%;
  background: var(--fp-surface-3);
  color: var(--fp-text-subtle);
  font-size: 18px;
}

.fp-empty__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fp-text);
}

.fp-empty__desc {
  max-width: 380px;
  font-size: 13px;
  color: var(--fp-text-muted);
}

.fp-empty__action:not(:empty) {
  margin-top: var(--sp-2);
}
</style>
