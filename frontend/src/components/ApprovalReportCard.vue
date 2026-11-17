<script setup lang="ts">
import { computed } from 'vue'
import type { JsonObject } from '@/api/types'
import { parseApprovalReport } from '@/utils/approval'

const props = defineProps<{ requestData?: JsonObject | null }>()

const report = computed(() => parseApprovalReport(props.requestData))

/** 风险等级 → 标签类型 */
function riskTagType(riskLevel: string): 'success' | 'warning' | 'danger' {
  const level = riskLevel.toUpperCase()
  if (level === 'HIGH') return 'danger'
  if (level === 'MEDIUM') return 'warning'
  return 'success'
}
</script>

<template>
  <div v-if="report" class="fp-approval-report">
    <div class="fp-approval-report__head">
      <span class="fp-label">待审批报告</span>
      <el-tag v-if="report.riskLevel" size="small" :type="riskTagType(report.riskLevel)">
        风险等级 {{ report.riskLevel }}
      </el-tag>
    </div>
    <div v-if="report.title" class="fp-approval-report__title">{{ report.title }}</div>
    <p v-if="report.summary" class="fp-approval-report__summary">{{ report.summary }}</p>
    <section
      v-for="section in report.sections"
      :key="section.title"
      class="fp-approval-report__section"
    >
      <h4 class="fp-approval-report__section-title">{{ section.title }}</h4>
      <p class="fp-approval-report__section-content">{{ section.content }}</p>
    </section>
  </div>
</template>

<style scoped>
/* 待审批的报告：审批人需要看到自己在批准什么 */
.fp-approval-report {
  display: grid;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-surface);
}

.fp-approval-report__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.fp-approval-report__title {
  font-size: 13.5px;
  font-weight: 600;
}

.fp-approval-report__summary {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fp-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.fp-approval-report__section {
  padding-top: var(--sp-2);
  border-top: 1px solid var(--fp-border);
}

.fp-approval-report__section-title {
  margin: 0 0 var(--sp-1);
  font-size: 12px;
  font-weight: 600;
  color: var(--fp-text-subtle);
}

.fp-approval-report__section-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fp-text);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
