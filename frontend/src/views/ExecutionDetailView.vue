<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CircleClose, Refresh, VideoPause } from '@element-plus/icons-vue'
import { approvalApi } from '@/api/client'
import type { Approval, ApprovalDecision, Id, JsonObject } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useExecutionStore } from '@/stores/executions'
import PageHeader from '@/components/PageHeader.vue'
import StatTile from '@/components/StatTile.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { executionStatusMeta, nodeStatusMeta, approvalStatusMeta, TERMINAL_EXECUTION_STATUSES } from '@/utils/constants'
import { formatDateTime, formatDuration, formatSpan } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const store = useExecutionStore()

const executionId = computed(() => String(route.params.id))

const loading = ref(true)
const error = ref('')
const cancelling = ref(false)
const approvals = ref<Approval[]>([])

const execution = computed(() => store.current)
const progress = computed(() => {
  const data = execution.value
  if (!data || data.totalNodes === 0) return 0
  return Math.min(100, Math.round((data.completedNodes / data.totalNodes) * 100))
})

const isTerminal = computed(() =>
  TERMINAL_EXECUTION_STATUSES.includes(execution.value?.status ?? '')
)

const canCancel = computed(
  () => !!execution.value && !isTerminal.value && execution.value.status !== 'CANCELED'
)

const relatedApprovals = computed(() =>
  approvals.value.filter((item) => item.workflowExecutionId === executionId.value)
)

const pendingApproval = computed(
  () => relatedApprovals.value.find((item) => item.status === 'PENDING') ?? null
)

/** 执行输出：成功结束后由各节点的产出汇总而成 */
const output = computed<JsonObject>(() => execution.value?.output ?? {})
const outputNodeCount = computed(() => Object.keys(output.value).length)
const hasOutput = computed(() => outputNodeCount.value > 0)
const formattedOutput = computed(() => JSON.stringify(output.value, null, 2))

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const detail = await store.loadDetail(executionId.value)
    if (!detail) {
      error.value = store.error || '执行详情加载失败'
      loading.value = false
      return
    }
    await Promise.all([store.loadNodes(executionId.value), store.loadHistory(executionId.value)])
    await loadApprovals()
    syncStream()
  } catch {
    error.value = '执行详情加载失败，请检查后端服务是否已启动'
  } finally {
    loading.value = false
  }
}

async function loadApprovals(): Promise<void> {
  try {
    const page = await approvalApi.list({ page: 1, size: 50, status: 'PENDING' })
    approvals.value = page?.records ?? []
  } catch {
    approvals.value = []
  }
}

/** 根据执行状态建立或断开 SSE */
function syncStream(): void {
  if (isTerminal.value) {
    store.disconnectStream()
    return
  }
  if (!auth.token) return
  store.connectStream(executionId.value, auth.token)
}

async function toggleNode(row: { nodeExecutionId: Id }): Promise<void> {
  if (!store.attemptsByNode[row.nodeExecutionId]) {
    await store.loadAttempts(executionId.value, row.nodeExecutionId)
  }
}

/** 展开节点行时按需加载该节点的重试记录 */
async function onExpandChange(row: { nodeExecutionId: Id }, expanded: boolean): Promise<void> {
  if (expanded) await toggleNode(row)
}

async function cancelExecution(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认取消该执行吗？正在运行的节点将被中断。', '取消执行', {
      confirmButtonText: '取消执行',
      cancelButtonText: '返回',
      type: 'warning'
    })
  } catch {
    return
  }
  cancelling.value = true
  const ok = await store.cancel(executionId.value)
  cancelling.value = false
  if (ok) {
    ElMessage.success('已发送取消请求')
    syncStream()
  }
}

async function decide(approval: Approval, decision: ApprovalDecision): Promise<void> {
  const prompt = await ElMessageBox.prompt(
    decision === 'APPROVED' ? '可填写审批意见' : '请填写驳回原因',
    decision === 'APPROVED' ? '通过审批' : '驳回审批',
    {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '可选'
    }
  ).catch(() => null)

  if (prompt === null) return

  try {
    await approvalApi.decide(approval.approvalId, {
      decision,
      comment: prompt.value || undefined
    })
    ElMessage.success(decision === 'APPROVED' ? '审批已通过' : '审批已驳回')
    await loadApprovals()
  } catch {
    // 错误提示由 api 拦截器统一处理
  }
}

watch(
  () => execution.value?.status,
  () => {
    syncStream()
  }
)

watch(
  () => route.params.id,
  () => {
    store.reset()
    void loadAll()
  }
)

onMounted(loadAll)
onBeforeUnmount(() => {
  store.disconnectStream()
})
</script>

<template>
  <div class="fp-page fp-page--wide">
    <el-button text :icon="ArrowLeft" class="fp-ed__back" @click="router.back()">返回</el-button>

    <ErrorState v-if="error" :message="error" @retry="loadAll" />

    <template v-else>
      <PageHeader
        :title="execution?.executionNo ?? '执行详情'"
        :description="`工作流 #${execution?.workflowId ?? '—'} · 版本 ${
          execution?.workflowVersionId ?? '—'
        }`"
      >
        <StatusTag
          v-if="execution"
          :label="executionStatusMeta(execution.status).label"
          :tone="executionStatusMeta(execution.status).tone"
          :pulse="execution.status === 'RUNNING'"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button
          v-if="canCancel"
          type="danger"
          plain
          :icon="VideoPause"
          :loading="cancelling"
          @click="cancelExecution"
        >
          取消执行
        </el-button>
      </PageHeader>

      <!-- SSE 连接指示 -->
      <div
        class="fp-ed__stream"
        :class="{
          'fp-ed__stream--live': store.isStreaming,
          'fp-ed__stream--warn': store.isReconnecting
        }"
      >
        <span class="fp-ed__stream-dot" aria-hidden="true" />
        <span v-if="store.isStreaming">实时事件流已连接</span>
        <span v-else-if="store.streamStatus === 'reconnecting'">连接中断，正在重连…</span>
        <span v-else-if="store.streamStatus === 'connecting'">正在建立实时事件流…</span>
        <span v-else-if="store.streamStatus === 'closed'">实时事件流已关闭</span>
        <span v-else>实时事件流未连接</span>
        <span v-if="store.events.length" class="fp-ed__stream-count">
          · {{ store.events.length }} 条事件
        </span>
      </div>

      <!-- 待处理审批 -->
      <div v-if="pendingApproval" class="fp-card fp-ed__approval">
        <div class="fp-card__body">
          <div class="fp-ed__approval-head">
            <div class="fp-grow">
              <div class="fp-label">等待你的审批</div>
              <div class="fp-ed__approval-title">{{ pendingApproval.title }}</div>
              <p v-if="pendingApproval.description" class="fp-ed__approval-desc">
                {{ pendingApproval.description }}
              </p>
            </div>
            <div class="fp-ed__approval-actions">
              <el-button type="success" plain @click="decide(pendingApproval, 'APPROVED')">
                通过
              </el-button>
              <el-button type="danger" plain @click="decide(pendingApproval, 'REJECTED')">
                驳回
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 概览 -->
      <div class="fp-ed__tiles">
        <StatTile label="节点进度" :value="`${execution?.completedNodes ?? 0}/${execution?.totalNodes ?? 0}`" :hint="`完成度 ${progress}%`" accent />
        <StatTile label="失败节点" :value="execution?.failedNodes ?? 0" :tone="(execution?.failedNodes ?? 0) > 0 ? 'danger' : 'default'" />
        <StatTile label="耗时" :value="formatSpan(execution?.startedAt, execution?.finishedAt)" hint="从开始到结束" />
        <StatTile label="开始时间" :value="formatDateTime(execution?.startedAt)" :hint="execution?.finishedAt ? `结束 ${formatDateTime(execution.finishedAt)}` : '尚未结束'" />
      </div>

      <!-- 工作流输出 -->
      <div v-if="hasOutput" class="fp-card">
        <div class="fp-card__head">
          <h2 class="fp-section-title">执行输出</h2>
          <span class="fp-muted">{{ outputNodeCount }} 个节点的产出</span>
        </div>
        <div class="fp-card__body">
          <pre class="fp-ed__output">{{ formattedOutput }}</pre>
        </div>
      </div>

      <!-- 错误信息 -->
      <div v-if="execution?.errorMessage" class="fp-card fp-ed__error">
        <div class="fp-card__body">
          <div class="fp-ed__error-head">
            <el-icon class="fp-ed__error-icon"><CircleClose /></el-icon>
            <span class="fp-section-title">执行失败</span>
            <code v-if="execution.errorCode" class="fp-mono">{{ execution.errorCode }}</code>
          </div>
          <p class="fp-ed__error-msg">{{ execution.errorMessage }}</p>
        </div>
      </div>

      <div class="fp-ed__grid">
        <!-- 节点列表 -->
        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">节点执行</h2>
            <span class="fp-muted">{{ store.nodes.length }} 个节点</span>
          </div>
          <div class="fp-card__body fp-card__body--flush">
            <EmptyState
              v-if="!loading && store.nodes.length === 0"
              icon="◷"
              title="暂无节点执行记录"
              description="节点调度完成后会出现在这里。"
            />
            <el-table
              v-else
              v-loading="loading"
              :data="store.nodes"
              row-key="nodeExecutionId"
              style="width: 100%"
              @expand-change="onExpandChange"
            >
              <el-table-column type="expand">
                <template #default="{ row }: { row: { nodeExecutionId: Id; nodeKey: string; status: string; attempt: number; maxAttempts: number; errorMessage?: string | null } }">
                  <div class="fp-ed__attempts">
                    <div class="fp-row fp-row--between">
                      <span class="fp-label">重试记录</span>
                      <el-button size="small" text @click="toggleNode(row)">刷新</el-button>
                    </div>
                    <p v-if="row.errorMessage" class="fp-ed__attempts-error">{{ row.errorMessage }}</p>
                    <el-table
                      :data="store.attemptsByNode[row.nodeExecutionId] ?? []"
                      size="small"
                      style="width: 100%"
                    >
                      <el-table-column label="#" width="60">
                        <template #default="{ row: attempt }: { row: { attemptNo: number } }">
                          <span class="fp-mono">{{ attempt.attemptNo }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="状态" width="110">
                        <template #default="{ row: attempt }: { row: { status: string } }">
                          <StatusTag
                            :label="nodeStatusMeta(attempt.status).label"
                            :tone="nodeStatusMeta(attempt.status).tone"
                          />
                        </template>
                      </el-table-column>
                      <el-table-column label="Worker" width="120">
                        <template #default="{ row: attempt }: { row: { workerId?: string | null } }">
                          <span class="fp-mono fp-subtle">{{ attempt.workerId || '—' }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="耗时" width="100">
                        <template #default="{ row: attempt }: { row: { durationMs?: number | null } }">
                          <span class="fp-muted">{{ formatDuration(attempt.durationMs) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="错误" min-width="200">
                        <template #default="{ row: attempt }: { row: { errorCode?: string | null; errorMessage?: string | null } }">
                          <div v-if="attempt.errorCode" class="fp-mono fp-ed__attempt-code">
                            {{ attempt.errorCode }}
                          </div>
                          <span class="fp-muted">{{ attempt.errorMessage || '—' }}</span>
                        </template>
                      </el-table-column>
                    </el-table>
                    <p
                      v-if="!store.attemptsByNode[row.nodeExecutionId]?.length"
                      class="fp-subtle fp-ed__attempts-empty"
                    >
                      暂无重试记录
                    </p>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="节点" min-width="200">
                <template #default="{ row }: { row: { nodeKey: string; nodeType: string } }">
                  <div class="fp-ed__node-key">{{ row.nodeKey }}</div>
                  <div class="fp-ed__node-type">{{ row.nodeType }}</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }: { row: { status: string } }">
                  <StatusTag
                    :label="nodeStatusMeta(row.status).label"
                    :tone="nodeStatusMeta(row.status).tone"
                    :pulse="row.status === 'RUNNING'"
                  />
                </template>
              </el-table-column>
              <el-table-column label="尝试" width="90" align="right">
                <template #default="{ row }: { row: { attempt: number; maxAttempts: number } }">
                  <span class="fp-muted">{{ row.attempt }}/{{ row.maxAttempts }}</span>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="110">
                <template #default="{ row }: { row: { durationMs?: number | null } }">
                  <span class="fp-muted">{{ formatDuration(row.durationMs) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="错误" min-width="200">
                <template #default="{ row }: { row: { errorMessage?: string | null } }">
                  <span class="fp-ed__node-error">{{ row.errorMessage || '—' }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 事件时间线 -->
        <div class="fp-card fp-ed__timeline-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">事件时间线</h2>
            <span class="fp-muted">{{ store.events.length }} 条</span>
          </div>
          <div class="fp-card__body">
            <EmptyState
              v-if="store.events.length === 0"
              icon="⌁"
              title="暂无事件"
              description="执行开始后，实时事件会按序出现在这里。"
            />
            <ol v-else class="fp-ed__timeline">
              <li v-for="event in store.events" :key="event.sequenceNo" class="fp-ed__event">
                <span class="fp-ed__event-seq">{{ event.sequenceNo }}</span>
                <div class="fp-grow">
                  <div class="fp-ed__event-head">
                    <span class="fp-ed__event-type">{{ event.eventType }}</span>
                    <span v-if="event.nodeKey" class="fp-ed__event-node">{{ event.nodeKey }}</span>
                    <span class="fp-ed__event-time">{{ formatDateTime(event.createdAt) }}</span>
                  </div>
                  <pre
                    v-if="event.eventData && Object.keys(event.eventData).length"
                    class="fp-ed__event-data"
                  >{{ JSON.stringify(event.eventData, null, 2) }}</pre>
                </div>
              </li>
            </ol>
          </div>
        </div>
      </div>

      <!-- 关联审批 -->
      <div class="fp-card">
        <div class="fp-card__head">
          <h2 class="fp-section-title">关联审批</h2>
          <span class="fp-muted">{{ relatedApprovals.length }} 条</span>
        </div>
        <div class="fp-card__body fp-card__body--flush">
          <EmptyState
            v-if="relatedApprovals.length === 0"
            icon="✓"
            title="该执行没有待处理的审批"
            description="当工作流进入人工审批节点时，相关审批会显示在这里。"
          />
          <ul v-else class="fp-ed__approvals">
            <li v-for="approval in relatedApprovals" :key="approval.approvalId" class="fp-ed__approval-row">
              <div class="fp-grow">
                <div class="fp-ed__approval-title">{{ approval.title }}</div>
                <div class="fp-subtle">
                  {{ approval.approvalType }} · 申请于 {{ formatDateTime(approval.requestedAt) }}
                </div>
              </div>
              <StatusTag
                :label="approvalStatusMeta(approval.status).label"
                :tone="approvalStatusMeta(approval.status).tone"
              />
              <div v-if="approval.status === 'PENDING'" class="fp-row">
                <el-button size="small" type="success" plain @click="decide(approval, 'APPROVED')">
                  通过
                </el-button>
                <el-button size="small" type="danger" plain @click="decide(approval, 'REJECTED')">
                  驳回
                </el-button>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fp-ed__back {
  margin-bottom: var(--sp-2);
  margin-left: -8px;
}

.fp-ed__stream {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 6px var(--sp-3);
  margin-bottom: var(--sp-4);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-pill);
  width: fit-content;
  font-size: 12px;
  color: var(--fp-text-muted);
  background: var(--fp-surface);
}

.fp-ed__stream-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--fp-text-subtle);
}

.fp-ed__stream--live {
  color: var(--fp-success);
  border-color: var(--fp-success);
}

.fp-ed__stream--live .fp-ed__stream-dot {
  background: var(--fp-success);
  animation: fp-ed-pulse 1.8s ease-in-out infinite;
}

.fp-ed__stream--warn {
  color: var(--fp-warning);
  border-color: var(--fp-warning);
}

.fp-ed__stream--warn .fp-ed__stream-dot {
  background: var(--fp-warning);
  animation: fp-ed-pulse 1s ease-in-out infinite;
}

.fp-ed__stream-count {
  color: var(--fp-text-subtle);
}

@keyframes fp-ed-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.fp-ed__approval {
  margin-bottom: var(--sp-4);
  border-color: var(--fp-accent-border);
  background: var(--fp-accent-soft);
}

.fp-ed__approval-head {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

.fp-ed__approval-title {
  margin-top: 2px;
  font-size: 15px;
  font-weight: 600;
}

.fp-ed__approval-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--fp-text-muted);
}

.fp-ed__approval-actions {
  display: flex;
  gap: var(--sp-2);
}

.fp-ed__tiles {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}

.fp-ed__error {
  margin-bottom: var(--sp-5);
  border-color: var(--fp-danger);
}

.fp-ed__error-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.fp-ed__error-icon {
  color: var(--fp-danger);
  font-size: 18px;
}

.fp-ed__error-msg {
  margin-top: var(--sp-2);
  font-size: 13px;
  color: var(--fp-text-muted);
  word-break: break-word;
}

.fp-ed__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: var(--sp-5);
  margin-bottom: var(--sp-5);
  align-items: start;
}

.fp-ed__node-key {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.fp-ed__node-type {
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-ed__node-error {
  font-size: 12px;
  color: var(--fp-danger);
}

.fp-ed__attempts {
  padding: var(--sp-3) var(--sp-5) var(--sp-4);
  background: var(--fp-surface-2);
}

.fp-ed__attempts-error {
  margin: var(--sp-2) 0;
  font-size: 12.5px;
  color: var(--fp-danger);
}

.fp-ed__attempts-empty {
  margin-top: var(--sp-2);
  font-size: 12.5px;
}

.fp-ed__attempt-code {
  font-size: 11px;
  color: var(--fp-danger);
}

.fp-ed__timeline-card .fp-card__body {
  max-height: 620px;
  overflow-y: auto;
}

.fp-ed__timeline {
  margin: 0;
  padding: 0 0 0 var(--sp-5);
  list-style: none;
  border-left: 1px solid var(--fp-border);
}

.fp-ed__event {
  position: relative;
  display: flex;
  gap: var(--sp-3);
  padding: 0 0 var(--sp-4) var(--sp-4);
}

.fp-ed__event::before {
  content: '';
  position: absolute;
  left: calc(var(--sp-5) * -1 - 4px);
  top: 6px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--fp-border-strong);
}

.fp-ed__event-seq {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fp-text-subtle);
  padding-top: 2px;
}

.fp-ed__event-head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.fp-ed__event-type {
  font-size: 13px;
  font-weight: 600;
}

.fp-ed__event-node {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fp-accent);
}

.fp-ed__event-time {
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-ed__event-data {
  margin: 6px 0 0;
  padding: var(--sp-2) var(--sp-3);
  max-height: 200px;
  overflow: auto;
  border-radius: var(--fp-radius-sm);
  background: var(--fp-surface-3);
  color: var(--fp-text-muted);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.fp-ed__output {
  margin: 0;
  padding: var(--sp-3);
  max-height: 420px;
  overflow: auto;
  border-radius: var(--fp-radius-sm);
  background: var(--fp-surface-3);
  color: var(--fp-text-muted);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.fp-ed__approvals {
  margin: 0;
  padding: 0;
  list-style: none;
}

.fp-ed__approval-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-5);
  border-bottom: 1px solid var(--fp-border);
  flex-wrap: wrap;
}

.fp-ed__approval-row:last-child {
  border-bottom: none;
}

.fp-ed__approval-title {
  font-size: 13.5px;
  font-weight: 550;
}

@media (max-width: 1200px) {
  .fp-ed__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .fp-ed__tiles {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--sp-3);
  }
}
</style>
