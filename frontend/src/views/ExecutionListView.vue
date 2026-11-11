<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { executionApi } from '@/api/client'
import type { Execution } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { executionStatusMeta, EXECUTION_STATUS_OPTIONS } from '@/utils/constants'
import { formatDateTime, formatSpan } from '@/utils/format'

const router = useRouter()

const list = ref<Execution[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const query = reactive({
  page: 1,
  size: 10,
  status: '',
  workflowId: ''
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await executionApi.list({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      workflowId: query.workflowId ? String(query.workflowId) : undefined
    })
    list.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch {
    error.value = '执行记录加载失败，请检查后端服务是否已启动'
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
  query.workflowId = ''
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
      title="执行记录"
      description="按状态筛选工作流执行实例，点击进入实时执行详情。"
    >
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <div class="fp-card fp-exec__toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="fp-exec__status">
        <el-option
          v-for="option in EXECUTION_STATUS_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-input
        v-model="query.workflowId"
        :prefix-icon="Search"
        placeholder="按工作流 ID 筛选"
        clearable
        class="fp-exec__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button type="primary" plain @click="search">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </div>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <div class="fp-card">
      <div class="fp-card__body fp-card__body--flush">
        <el-table
          v-loading="loading"
          :data="list"
          row-key="executionId"
          style="width: 100%"
          @row-click="(row: Execution) => router.push({ name: 'execution-detail', params: { id: row.executionId } })"
        >
          <el-table-column label="执行编号" min-width="190">
            <template #default="{ row }: { row: Execution }">
              <span class="fp-mono fp-exec__no">{{ row.executionNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }: { row: Execution }">
              <StatusTag
                :label="executionStatusMeta(row.status).label"
                :tone="executionStatusMeta(row.status).tone"
                :pulse="row.status === 'RUNNING'"
              />
            </template>
          </el-table-column>
          <el-table-column label="进度" width="150">
            <template #default="{ row }: { row: Execution }">
              <div class="fp-exec__progress">
                <div class="fp-exec__progress-bar">
                  <div
                    class="fp-exec__progress-fill"
                    :class="{ 'fp-exec__progress-fill--failed': row.failedNodes > 0 }"
                    :style="{
                      width: `${
                        row.totalNodes === 0
                          ? 0
                          : Math.min(100, (row.completedNodes / row.totalNodes) * 100)
                      }%`
                    }"
                  />
                </div>
                <span class="fp-exec__progress-text">
                  {{ row.completedNodes }}/{{ row.totalNodes }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="工作流" width="110">
            <template #default="{ row }: { row: Execution }">
              <span class="fp-mono fp-subtle">#{{ row.workflowId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="110">
            <template #default="{ row }: { row: Execution }">
              <span class="fp-muted">{{ formatSpan(row.startedAt, row.finishedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }: { row: Execution }">
              <span class="fp-muted">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="right">
            <template #default="{ row }: { row: Execution }">
              <el-button
                size="small"
                text
                type="primary"
                @click.stop="router.push({ name: 'execution-detail', params: { id: row.executionId } })"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-if="!loading && !error && list.length === 0"
          icon="◷"
          title="暂无执行记录"
          description="在工作流详情页点击「运行」即可启动一次执行。"
        />
      </div>

      <div v-if="total > 0" class="fp-exec__pager">
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
  </div>
</template>

<style scoped>
.fp-exec__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}

.fp-exec__status {
  width: 160px;
}

.fp-exec__search {
  max-width: 260px;
}

.fp-exec__no {
  font-size: 12.5px;
  color: var(--fp-text);
}

.fp-exec__progress {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.fp-exec__progress-bar {
  flex: 1 1 auto;
  height: 6px;
  border-radius: var(--fp-pill);
  background: var(--fp-surface-3);
  overflow: hidden;
}

.fp-exec__progress-fill {
  height: 100%;
  border-radius: var(--fp-pill);
  background: var(--fp-success);
  transition: width 0.4s var(--ease);
}

.fp-exec__progress-fill--failed {
  background: var(--fp-danger);
}

.fp-exec__progress-text {
  flex: none;
  font-size: 12px;
  color: var(--fp-text-muted);
  font-variant-numeric: tabular-nums;
}

.fp-exec__pager {
  display: flex;
  justify-content: flex-end;
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--fp-border);
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 768px) {
  .fp-exec__status,
  .fp-exec__search {
    width: 100%;
    max-width: 100%;
  }
}
</style>
