<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, CircleCheck, Promotion, VideoPlay } from '@element-plus/icons-vue'
import { workflowApi } from '@/api/client'
import type { DagValidationResult, Id, Workflow, WorkflowDefinition, WorkflowVersion } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import JsonEditor from '@/components/JsonEditor.vue'
import { NODE_TYPE_LABELS, workflowStatusMeta } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const workflowId = computed(() => String(route.params.id))

const workflow = ref<Workflow | null>(null)
const versions = ref<WorkflowVersion[]>([])
const validation = ref<DagValidationResult | null>(null)
const loading = ref(true)
const error = ref('')
const validating = ref(false)
const publishing = ref<Id | null>(null)
const running = ref(false)

// 运行对话框：工作流需要输入参数，例如技术尽调场景需要仓库地址。
const runDialogVisible = ref(false)
const runInput = ref('{\n  "repositoryUrl": ""\n}')
const runInputError = ref('')

const parsedRunInput = computed<Record<string, unknown>>(() => {
  try {
    const parsed = JSON.parse(runInput.value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
})

const runInputValid = computed<boolean>(() => {
  try {
    const parsed = JSON.parse(runInput.value)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
  } catch {
    return false
  }
})

const definition = computed<WorkflowDefinition>(() => {
  if (workflow.value?.definition) return workflow.value.definition
  const published = versions.value.find((version) => version.definition)
  if (published?.definition) return published.definition
  return { nodes: [] }
})

const nodeCount = computed(() => definition.value.nodes?.length ?? 0)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [detail, versionList] = await Promise.all([
      workflowApi.detail(workflowId.value),
      workflowApi.versions(workflowId.value)
    ])
    workflow.value = detail
    versions.value = versionList ?? []
  } catch {
    error.value = '工作流详情加载失败，请检查后端服务是否已启动'
    workflow.value = null
    versions.value = []
  } finally {
    loading.value = false
  }
}

async function validateDefinition(): Promise<void> {
  validating.value = true
  validation.value = null
  try {
    validation.value = await workflowApi.validate(definition.value)
    if (validation.value.valid) {
      ElMessage.success('DAG 校验通过')
    } else {
      ElMessage.warning('DAG 校验未通过，请检查错误项')
    }
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    validating.value = false
  }
}

async function publish(version: WorkflowVersion): Promise<void> {
  publishing.value = version.id
  try {
    await workflowApi.publish(workflowId.value, { versionId: version.id })
    ElMessage.success(`版本 v${version.version} 已发布`)
    await load()
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    publishing.value = null
  }
}

function openRunDialog(): void {
  const publishable = versions.value.find((version) => version.status === 'PUBLISHED')
  if (!publishable) {
    ElMessage.warning('请先发布一个版本后再运行')
    return
  }
  runInputError.value = ''
  runDialogVisible.value = true
}

async function confirmRun(): Promise<void> {
  const publishable = versions.value.find((version) => version.status === 'PUBLISHED')
  if (!publishable) {
    ElMessage.warning('请先发布一个版本后再运行')
    return
  }
  if (!runInputValid.value) {
    runInputError.value = '输入必须是合法的 JSON 对象'
    return
  }
  running.value = true
  try {
    const result = await workflowApi.startExecution(workflowId.value, {
      workflowVersionId: publishable.id,
      input: parsedRunInput.value
    })
    ElMessage.success('执行已启动')
    runDialogVisible.value = false
    await router.push({ name: 'execution-detail', params: { id: result.executionId } })
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="fp-page">
    <el-button text :icon="ArrowLeft" class="fp-wfdetail__back" @click="router.back()">
      返回
    </el-button>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <template v-else>
      <PageHeader
        :title="workflow?.name ?? '工作流详情'"
        :description="workflow?.description || '暂无描述'"
      >
        <StatusTag
          v-if="workflow"
          :label="workflowStatusMeta(workflow.status).label"
          :tone="workflowStatusMeta(workflow.status).tone"
        />
        <el-button :loading="validating" @click="validateDefinition">校验 DAG</el-button>
        <el-button type="primary" :icon="VideoPlay" :loading="running" @click="openRunDialog">
          运行
        </el-button>
      </PageHeader>

      <div v-if="loading" class="fp-card">
        <div class="fp-card__body">
          <el-skeleton animated :rows="6" />
        </div>
      </div>

      <div v-else class="fp-stack">
        <div class="fp-wfdetail__meta">
          <div class="fp-wfdetail__meta-item">
            <span class="fp-label">工作流 ID</span>
            <span class="fp-mono">#{{ workflowId }}</span>
          </div>
          <div class="fp-wfdetail__meta-item">
            <span class="fp-label">分类</span>
            <span>{{ workflow?.category || '—' }}</span>
          </div>
          <div class="fp-wfdetail__meta-item">
            <span class="fp-label">节点数</span>
            <span>{{ nodeCount }}</span>
          </div>
          <div class="fp-wfdetail__meta-item">
            <span class="fp-label">创建时间</span>
            <span>{{ formatDateTime(workflow?.createdAt) }}</span>
          </div>
        </div>

        <!-- DAG 校验结果 -->
        <div v-if="validation" class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">DAG 校验结果</h2>
            <StatusTag
              :label="validation.valid ? '校验通过' : '校验失败'"
              :tone="validation.valid ? 'success' : 'danger'"
            />
          </div>
          <div class="fp-card__body">
            <div v-if="validation.topologicalOrder.length" class="fp-wfdetail__order">
              <span class="fp-label">拓扑顺序</span>
              <div class="fp-wfdetail__order-list">
                <template v-for="(nodeId, index) in validation.topologicalOrder" :key="nodeId">
                  <code class="fp-wfdetail__node">{{ nodeId }}</code>
                  <span v-if="index < validation.topologicalOrder.length - 1" class="fp-wfdetail__arrow">
                    →
                  </span>
                </template>
              </div>
            </div>
            <ul v-if="validation.errors.length" class="fp-wfdetail__issues">
              <li v-for="(item, index) in validation.errors" :key="`e-${index}`">
                <span class="fp-wfdetail__issue-tag fp-wfdetail__issue-tag--danger">错误</span>
                {{ item }}
              </li>
            </ul>
            <ul v-if="validation.warnings.length" class="fp-wfdetail__issues">
              <li v-for="(item, index) in validation.warnings" :key="`w-${index}`">
                <span class="fp-wfdetail__issue-tag fp-wfdetail__issue-tag--warning">警告</span>
                {{ item }}
              </li>
            </ul>
            <p
              v-if="
                !validation.errors.length &&
                !validation.warnings.length &&
                !validation.topologicalOrder.length
              "
              class="fp-muted"
            >
              校验未返回额外信息。
            </p>
          </div>
        </div>

        <!-- 节点列表 -->
        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">节点定义</h2>
            <span class="fp-muted">{{ nodeCount }} 个节点</span>
          </div>
          <div class="fp-card__body fp-card__body--flush">
            <EmptyState
              v-if="nodeCount === 0"
              icon="◇"
              title="该工作流暂无节点定义"
              description="创建新版本后可在版本列表中编辑节点。"
            />
            <ul v-else class="fp-wfdetail__nodes">
              <li v-for="node in definition.nodes" :key="node.id" class="fp-wfdetail__node-row">
                <div class="fp-grow">
                  <div class="fp-wfdetail__node-title">
                    {{ node.name || node.id }}
                    <span class="fp-wfdetail__node-id">{{ node.id }}</span>
                  </div>
                  <div class="fp-wfdetail__node-deps">
                    <span class="fp-label">依赖</span>
                    <template v-if="node.dependencies?.length">
                      <code v-for="dep in node.dependencies" :key="dep" class="fp-mono">{{ dep }}</code>
                    </template>
                    <span v-else class="fp-subtle">无</span>
                  </div>
                </div>
                <span class="fp-wfdetail__node-type">
                  {{ NODE_TYPE_LABELS[node.type] ?? node.type }}
                </span>
              </li>
            </ul>
          </div>
        </div>

        <!-- 版本列表 -->
        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">版本</h2>
            <span class="fp-muted">{{ versions.length }} 个版本</span>
          </div>
          <div class="fp-card__body fp-card__body--flush">
            <EmptyState
              v-if="versions.length === 0"
              icon="⌥"
              title="暂无版本"
              description="发布新版本后将在此处展示。"
            />
            <el-table v-else :data="versions" style="width: 100%">
              <el-table-column label="版本" width="100">
                <template #default="{ row }: { row: WorkflowVersion }">
                  <span class="fp-mono">v{{ row.version }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }: { row: WorkflowVersion }">
                  <StatusTag
                    :label="row.status === 'PUBLISHED' ? '已发布' : '草稿'"
                    :tone="row.status === 'PUBLISHED' ? 'success' : 'neutral'"
                  />
                </template>
              </el-table-column>
              <el-table-column label="定义哈希" min-width="200">
                <template #default="{ row }: { row: WorkflowVersion }">
                  <span class="fp-mono fp-subtle">{{ row.definitionHash || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="发布时间" width="180">
                <template #default="{ row }: { row: WorkflowVersion }">
                  <span class="fp-muted">{{ formatDateTime(row.publishedAt || row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="right">
                <template #default="{ row }: { row: WorkflowVersion }">
                  <el-button
                    v-if="row.status !== 'PUBLISHED'"
                    size="small"
                    type="primary"
                    plain
                    :icon="Promotion"
                    :loading="publishing === row.id"
                    @click="publish(row)"
                  >
                    发布
                  </el-button>
                  <span v-else class="fp-subtle">
                    <el-icon><CircleCheck /></el-icon>
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 定义原文 -->
        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">定义原文</h2>
          </div>
          <div class="fp-card__body">
            <JsonEditor :model-value="JSON.stringify(definition, null, 2)" :rows="16" readonly />
          </div>
        </div>
      </div>
    </template>

    <!-- 运行对话框：工作流的输入参数，例如技术尽调场景的仓库地址 -->
    <el-dialog
      v-model="runDialogVisible"
      title="运行工作流"
      width="560px"
      :close-on-click-modal="false"
      append-to-body
    >
      <p class="fp-subtle fp-run__hint">
        工作流的输入以 JSON 传入。技术尽调场景需要填写 <code>repositoryUrl</code>，
        后端会校验它必须指向 github.com 上的公开仓库。
      </p>
      <el-input
        v-model="runInput"
        type="textarea"
        :rows="8"
        class="fp-mono"
        spellcheck="false"
        placeholder='{ "repositoryUrl": "https://github.com/owner/repo" }'
      />
      <p v-if="runInputError" class="fp-run__error">{{ runInputError }}</p>
      <template #footer>
        <el-button @click="runDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="running" :disabled="!runInputValid" @click="confirmRun">
          开始执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fp-wfdetail__back {
  margin-bottom: var(--sp-2);
  margin-left: -8px;
}

.fp-run__hint {
  margin: 0 0 var(--sp-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.fp-run__hint code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--fp-surface-3);
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.fp-run__error {
  margin: var(--sp-2) 0 0;
  font-size: 12.5px;
  color: var(--fp-danger);
}

.fp-wfdetail__meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  background: var(--fp-surface);
  border: 1px solid var(--fp-border);
  border-radius: var(--fp-radius);
}

.fp-wfdetail__meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.fp-wfdetail__order-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

.fp-wfdetail__node {
  padding: 2px 8px;
  border-radius: var(--fp-radius-sm);
  background: var(--fp-accent-soft);
  color: var(--fp-accent);
}

.fp-wfdetail__arrow {
  color: var(--fp-text-subtle);
}

.fp-wfdetail__issues {
  margin: var(--sp-3) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.fp-wfdetail__issue-tag {
  display: inline-block;
  margin-right: 8px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.fp-wfdetail__issue-tag--danger {
  background: var(--fp-danger-soft);
  color: var(--fp-danger);
}

.fp-wfdetail__issue-tag--warning {
  background: var(--fp-warning-soft);
  color: var(--fp-warning);
}

.fp-wfdetail__nodes {
  margin: 0;
  padding: 0;
  list-style: none;
}

.fp-wfdetail__node-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-3) var(--sp-5);
  border-bottom: 1px solid var(--fp-border);
}

.fp-wfdetail__node-row:last-child {
  border-bottom: none;
}

.fp-wfdetail__node-title {
  font-size: 13.5px;
  font-weight: 550;
}

.fp-wfdetail__node-id {
  margin-left: 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-wfdetail__node-deps {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  font-size: 12px;
  color: var(--fp-text-muted);
}

.fp-wfdetail__node-type {
  flex: none;
  padding: 2px 10px;
  border-radius: var(--fp-pill);
  background: var(--fp-surface-3);
  font-size: 12px;
  color: var(--fp-text-muted);
}

@media (max-width: 900px) {
  .fp-wfdetail__meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
