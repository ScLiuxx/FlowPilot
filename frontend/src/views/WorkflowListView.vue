<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { workflowApi } from '@/api/client'
import type { Id, Workflow, WorkflowDefinition } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '@/components/StatusTag.vue'
import JsonEditor from '@/components/JsonEditor.vue'
import { workflowStatusMeta, WORKFLOW_STATUS_OPTIONS } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

const SAMPLE_DEFINITION = JSON.stringify(
  {
    nodes: [
      {
        id: 'collect_repository',
        name: '采集仓库信息',
        type: 'HTTP',
        dependencies: [],
        config: { operation: 'get_repository' },
        retryPolicy: { maxAttempts: 3, initialDelaySeconds: 2, maxDelaySeconds: 60, multiplier: 2 },
        timeoutPolicy: { timeoutSeconds: 30 }
      },
      {
        id: 'analyze_repository',
        name: '分析仓库',
        type: 'AGENT',
        dependencies: ['collect_repository'],
        config: { agentType: 'REPOSITORY_ANALYST', timeoutSeconds: 300, maxToolCalls: 20 }
      }
    ]
  },
  null,
  2
)

const list = ref<Workflow[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const query = reactive({
  page: 1,
  size: 10,
  status: '',
  keyword: ''
})

/* ------------------------------------------------------------- 对话框 --- */
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const submitting = ref(false)
const formError = ref('')
const editingId = ref<Id | null>(null)
const form = reactive({
  name: '',
  description: '',
  category: '',
  definition: SAMPLE_DEFINITION
})

const isCreate = computed(() => dialogMode.value === 'create')

function openCreate(): void {
  dialogMode.value = 'create'
  editingId.value = null
  form.name = ''
  form.description = ''
  form.category = ''
  form.definition = SAMPLE_DEFINITION
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(workflow: Workflow): void {
  dialogMode.value = 'edit'
  editingId.value = workflow.id
  form.name = workflow.name
  form.description = workflow.description ?? ''
  form.category = workflow.category ?? ''
  form.definition = workflow.definition ? JSON.stringify(workflow.definition, null, 2) : SAMPLE_DEFINITION
  formError.value = ''
  dialogVisible.value = true
}

function parseDefinition(): WorkflowDefinition | null {
  try {
    const parsed: unknown = JSON.parse(form.definition)
    if (!parsed || typeof parsed !== 'object') {
      formError.value = '定义必须是一个 JSON 对象'
      return null
    }
    const nodes = (parsed as { nodes?: unknown }).nodes
    if (!Array.isArray(nodes)) {
      formError.value = '定义缺少 nodes 数组'
      return null
    }
    return parsed as WorkflowDefinition
  } catch (error) {
    formError.value = error instanceof Error ? error.message : 'JSON 解析失败'
    return null
  }
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    formError.value = '请填写工作流名称'
    return
  }
  const definition = parseDefinition()
  if (!definition) return

  submitting.value = true
  try {
    if (isCreate.value) {
      await workflowApi.create({
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        category: form.category.trim() || undefined,
        definition
      })
      ElMessage.success('工作流已创建')
    } else if (editingId.value !== null) {
      await workflowApi.update(editingId.value, {
        name: form.name.trim(),
        description: form.description.trim(),
        category: form.category.trim()
      })
      ElMessage.success('工作流已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

async function remove(workflow: Workflow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复，确认删除工作流「${workflow.name}」吗？`,
      '删除工作流',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await workflowApi.remove(workflow.id)
    ElMessage.success('工作流已删除')
    if (list.value.length === 1 && query.page > 1) query.page -= 1
    await load()
  } catch {
    // 错误提示由 api 拦截器统一处理
  }
}

/* --------------------------------------------------------------- 列表 --- */
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await workflowApi.list({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword.trim() || undefined
    })
    list.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch {
    error.value = '工作流列表加载失败，请检查后端服务是否已启动'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

function resetFilters(): void {
  query.status = ''
  query.keyword = ''
  query.page = 1
  void load()
}

watch(
  () => [query.status, query.page, query.size],
  () => {
    void load()
  }
)

onMounted(load)
</script>

<template>
  <div class="fp-page">
    <PageHeader
      title="工作流"
      description="管理 DAG 工作流定义，创建版本并发布上线。"
    >
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建工作流</el-button>
    </PageHeader>

    <div class="fp-card fp-wf__toolbar">
      <el-input
        v-model="query.keyword"
        :prefix-icon="Search"
        placeholder="按名称搜索工作流"
        clearable
        class="fp-wf__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable class="fp-wf__status">
        <el-option
          v-for="option in WORKFLOW_STATUS_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-button type="primary" plain @click="search">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </div>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <div class="fp-card">
      <div class="fp-card__body fp-card__body--flush">
        <el-table
          v-loading="loading"
          :data="list"
          row-key="id"
          style="width: 100%"
          empty-text="暂无工作流"
          @row-click="(row: Workflow) => router.push({ name: 'workflow-detail', params: { id: row.id } })"
        >
          <el-table-column label="名称" min-width="220">
            <template #default="{ row }: { row: Workflow }">
              <div class="fp-wf__name">{{ row.name }}</div>
              <div v-if="row.description" class="fp-wf__desc fp-truncate">
                {{ row.description }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="160">
            <template #default="{ row }: { row: Workflow }">
              <span class="fp-muted">{{ row.category || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }: { row: Workflow }">
              <StatusTag
                :label="workflowStatusMeta(row.status).label"
                :tone="workflowStatusMeta(row.status).tone"
              />
            </template>
          </el-table-column>
          <el-table-column label="最新版本" width="100" align="right">
            <template #default="{ row }: { row: Workflow }">
              <span class="fp-mono">v{{ row.latestVersion ?? 1 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }: { row: Workflow }">
              <span class="fp-muted">{{ formatDateTime(row.updatedAt || row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" align="right">
            <template #default="{ row }: { row: Workflow }">
              <el-button size="small" text type="primary" @click.stop="router.push({ name: 'workflow-detail', params: { id: row.id } })">
                详情
              </el-button>
              <el-button size="small" text :icon="Edit" @click.stop="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" :icon="Delete" @click.stop="remove(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-if="!loading && !error && list.length === 0"
          icon="◇"
          title="还没有工作流"
          description="创建第一个工作流，定义节点依赖关系后即可发布执行。"
        >
          <el-button type="primary" :icon="Plus" @click="openCreate">新建工作流</el-button>
        </EmptyState>
      </div>

      <div v-if="total > 0" class="fp-wf__pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isCreate ? '新建工作流' : '编辑工作流'"
      width="min(720px, 92vw)"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：GitHub 技术尽调" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="说明该工作流的用途"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="例如：GITHUB_DUE_DILIGENCE" />
        </el-form-item>
        <el-form-item v-if="isCreate" label="DAG 定义">
          <JsonEditor v-model="form.definition" :rows="16" />
        </el-form-item>
      </el-form>

      <p v-if="formError" class="fp-wf__form-error">{{ formError }}</p>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ isCreate ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fp-wf__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}

.fp-wf__search {
  max-width: 320px;
}

.fp-wf__status {
  width: 160px;
}

.fp-wf__name {
  font-size: 13.5px;
  font-weight: 550;
}

.fp-wf__desc {
  margin-top: 2px;
  max-width: 420px;
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-wf__pager {
  display: flex;
  justify-content: flex-end;
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--fp-border);
}

.fp-wf__form-error {
  margin-top: var(--sp-2);
  font-size: 12.5px;
  color: var(--fp-danger);
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 768px) {
  .fp-wf__search,
  .fp-wf__status {
    max-width: 100%;
    width: 100%;
  }
}
</style>
