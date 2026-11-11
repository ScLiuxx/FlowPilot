<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

onMounted(() => {
  if (auth.isAuthenticated) {
    void auth.refreshProfile()
  }
})
</script>

<template>
  <router-view />
</template>
