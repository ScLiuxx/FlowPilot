<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { approvalApi, monitoringApi } from '@/api/client'
import type { Approval, MonitoringOverview } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import StatTile from '@/components/StatTile.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { approvalStatusMeta } from '@/utils/constants'
import { formatNumber, formatRelative } from '@/utils/format'

const router = useRouter()

const overview = ref<MonitoringOverview | null>(null)
const pendingApprovals = ref<Approval[]>([])
const loading = ref(true)
const error = ref('')

const successRate = computed(() => {
  const data = overview.value
  if (!data || data.totalExecutions === 0) return '—'
  const rate = (data.succeededExecutions / data.totalExecutions) * 100
  return `${rate.toFixed(1)}%`
})

const totalTokens = computed(() => {
  const data = overview.value
  if (!data) return 0
  return data.totalTokenInput + data.totalTokenOutput
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, approvalsPage] = await Promise.all([
      monitoringApi.overview(),
      approvalApi.list({ page: 1, size: 5, status: 'PENDING' })
    ])
    overview.value = overviewData
    pendingApprovals.value = approvalsPage?.records ?? []
  } catch {
    error.value = '监控数据加载失败，请检查后端服务是否已启动'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="fp-page">
    <PageHeader
      title="概览"
      description="查看工作流执行状态、节点规模与待处理的审批任务。"
    >
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <!-- 骨架屏 -->
    <div v-if="loading && !overview" class="fp-dash__grid">
      <el-skeleton v-for="index in 4" :key="index" animated class="fp-dash__skeleton">
        <template #template>
          <el-skeleton-item variant="h1" style="width: 60px" />
          <el-skeleton-item variant="text" style="width: 100px; margin-top: 12px" />
        </template>
      </el-skeleton>
    </div>

    <template v-else-if="overview">
      <section class="fp-stack">
        <div class="fp-dash__grid">
          <StatTile
            label="执行总数"
            :value="formatNumber(overview.totalExecutions)"
            :hint="`成功率 ${successRate}`"
            accent
          />
          <StatTile
            label="执行中"
            :value="formatNumber(overview.runningExecutions)"
            hint="正在运行的实例"
            tone="accent"
          />
          <StatTile
            label="等待人工"
            :value="formatNumber(overview.waitingHumanExecutions)"
            hint="阻塞在审批节点"
            tone="warning"
          />
          <StatTile
            label="失败执行"
            :value="formatNumber(overview.failedExecutions)"
            hint="需要排查的实例"
            tone="danger"
          />
        </div>

        <div class="fp-dash__grid fp-dash__grid--3">
          <StatTile
            label="成功执行"
            :value="formatNumber(overview.succeededExecutions)"
            tone="success"
          />
          <StatTile label="累计节点" :value="formatNumber(overview.totalNodes)" hint="已调度的节点总数" />
          <StatTile
            label="待审批"
            :value="formatNumber(overview.pendingApprovals)"
            hint="等待人工决策"
            tone="warning"
          />
        </div>

        <div class="fp-card">
          <div class="fp-card__head">
            <div>
              <h2 class="fp-section-title">Token 用量</h2>
              <p class="fp-dash__hint">来自 LLM / Agent 调用的累计消耗</p>
            </div>
            <span class="fp-dash__total">合计 {{ formatNumber(totalTokens) }} tokens</span>
          </div>
          <div class="fp-card__body">
            <div class="fp-dash__tokens">
              <div class="fp-dash__token">
                <div class="fp-label">输入 Token</div>
                <div class="fp-metric">{{ formatNumber(overview.totalTokenInput) }}</div>
              </div>
              <div class="fp-dash__token-bar">
                <div
                  class="fp-dash__token-fill fp-dash__token-fill--in"
                  :style="{
                    width: `${
                      totalTokens === 0
                        ? 0
                        : (overview.totalTokenInput / totalTokens) * 100
                    }%`
                  }"
                />
              </div>
              <div class="fp-dash__token">
                <div class="fp-label">输出 Token</div>
                <div class="fp-metric">{{ formatNumber(overview.totalTokenOutput) }}</div>
              </div>
              <div class="fp-dash__token-bar">
                <div
                  class="fp-dash__token-fill"
                  :style="{
                    width: `${
                      totalTokens === 0
                        ? 0
                        : (overview.totalTokenOutput / totalTokens) * 100
                    }%`
                  }"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">待处理审批</h2>
            <el-button text type="primary" @click="router.push({ name: 'approvals' })">
              全部审批
              <el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
          </div>
          <div class="fp-card__body fp-card__body--flush">
            <EmptyState
              v-if="pendingApprovals.length === 0"
              icon="✓"
              title="没有待处理的审批"
              description="所有人工审批节点都已完成决策。"
            />
            <ul v-else class="fp-dash__approvals">
              <li v-for="approval in pendingApprovals" :key="approval.approvalId" class="fp-dash__approval">
                <div class="fp-grow">
                  <div class="fp-dash__approval-title fp-truncate">{{ approval.title }}</div>
                  <div class="fp-dash__approval-meta">
                    <span>{{ approval.approvalType }}</span>
                    <span>·</span>
                    <span>执行 #{{ approval.workflowExecutionId }}</span>
                    <span>·</span>
                    <span>{{ formatRelative(approval.requestedAt) }}</span>
                  </div>
                </div>
                <StatusTag
                  :label="approvalStatusMeta(approval.status).label"
                  :tone="approvalStatusMeta(approval.status).tone"
                />
                <el-button
                  size="small"
                  type="primary"
                  plain
                  @click="router.push({ name: 'execution-detail', params: { id: approval.workflowExecutionId } })"
                >
                  查看执行
                </el-button>
              </li>
            </ul>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.fp-dash__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
}

.fp-dash__grid--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.fp-dash__skeleton {
  padding: var(--sp-5);
  background: var(--fp-surface);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-radius);
}

.fp-dash__hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-dash__total {
  font-size: 12.5px;
  color: var(--fp-text-muted);
  font-variant-numeric: tabular-nums;
}

.fp-dash__tokens {
  display: grid;
  grid-template-columns: auto minmax(80px, 1fr);
  align-items: center;
  gap: var(--sp-3) var(--sp-4);
}

.fp-dash__token {
  min-width: 132px;
}

.fp-dash__token .fp-metric {
  margin-top: 2px;
  font-size: 20px;
}

.fp-dash__token-bar {
  height: 8px;
  border-radius: var(--fp-pill);
  background: var(--fp-surface-3);
  overflow: hidden;
}

.fp-dash__token-fill {
  height: 100%;
  border-radius: var(--fp-pill);
  background: var(--fp-accent);
  transition: width 0.4s var(--ease);
}

.fp-dash__token-fill--in {
  background: var(--fp-accent);
  opacity: 0.55;
}

.fp-dash__approvals {
  margin: 0;
  padding: 0;
  list-style: none;
}

.fp-dash__approval {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-5);
  border-bottom: 1px solid var(--fp-border);
}

.fp-dash__approval:last-child {
  border-bottom: none;
}

.fp-dash__approval-title {
  font-size: 13.5px;
  font-weight: 550;
}

.fp-dash__approval-meta {
  display: flex;
  gap: 6px;
  margin-top: 2px;
  font-size: 12px;
  color: var(--fp-text-subtle);
}

@media (max-width: 1100px) {
  .fp-dash__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .fp-dash__grid--3 {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .fp-dash__grid,
  .fp-dash__grid--3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--sp-3);
  }

  .fp-dash__tokens {
    grid-template-columns: 1fr;
  }

  .fp-dash__approval {
    flex-wrap: wrap;
  }
}
</style>
