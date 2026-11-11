<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 行数 */
    rows?: number
    placeholder?: string
    readonly?: boolean
  }>(),
  { rows: 18, placeholder: '{ "nodes": [] }', readonly: false }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const parseError = ref('')

const formatted = computed(() => {
  try {
    const parsed: unknown = JSON.parse(props.modelValue)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return ''
  }
})

watch(
  () => props.modelValue,
  (value) => {
    if (!value.trim()) {
      parseError.value = ''
      return
    }
    try {
      JSON.parse(value)
      parseError.value = ''
    } catch (error) {
      parseError.value = error instanceof Error ? error.message : 'JSON 格式错误'
    }
  },
  { immediate: true }
)

function format(): void {
  if (!formatted.value) return
  emit('update:modelValue', formatted.value)
}
</script>

<template>
  <div class="fp-json-editor">
    <div class="fp-json-editor__bar">
      <span class="fp-label">JSON 定义</span>
      <div class="fp-row">
        <span v-if="parseError" class="fp-json-editor__err">语法错误</span>
        <el-button v-if="!readonly" size="small" text @click="format">格式化</el-button>
      </div>
    </div>
    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="rows"
      :readonly="readonly"
      :placeholder="placeholder"
      resize="vertical"
      class="fp-json-editor__input"
      spellcheck="false"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <p v-if="parseError" class="fp-json-editor__msg">{{ parseError }}</p>
  </div>
</template>

<style scoped>
.fp-json-editor {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.fp-json-editor__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.fp-json-editor__err {
  font-size: 12px;
  color: var(--fp-danger);
}

.fp-json-editor__input :deep(textarea) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.6;
  tab-size: 2;
}

.fp-json-editor__msg {
  font-size: 12px;
  color: var(--fp-danger);
  word-break: break-all;
}
</style>
