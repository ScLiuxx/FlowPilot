<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <header class="fp-page-header">
    <div class="fp-grow">
      <h1 class="fp-page-header__title">{{ title }}</h1>
      <p v-if="description" class="fp-page-header__desc">{{ description }}</p>
    </div>
    <div class="fp-page-header__actions">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.fp-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

.fp-page-header__title {
  font-size: 21px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.fp-page-header__desc {
  margin-top: 4px;
  max-width: 68ch;
  font-size: 13px;
  color: var(--fp-text-muted);
}

.fp-page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .fp-page-header__title {
    font-size: 18px;
  }
}
</style>
