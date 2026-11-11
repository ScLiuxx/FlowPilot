<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { approvalApi } from '@/api/client'
import type { Approval, ApprovalDecision } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { approvalStatusMeta, APPROVAL_STATUS_OPTIONS } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

const list = ref<Approval[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const decidingId = ref<number | null>(null)

const query = reactive({
  page: 1,
  size: 10,
  status: 'PENDING'
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await approvalApi.list({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    list.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch {
    error.value = '审批列表加载失败，请检查后端服务是否已启动'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function decide(approval: Approval, decision: ApprovalDecision): Promise<void> {
  const prompt = await ElMessageBox.prompt(
    decision === 'APPROVED' ? '可填写审批意见（可选）' : '请填写驳回原因',
    decision === 'APPROVED' ? '通过审批' : '驳回审批',
    {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '可选'
    }
  ).catch(() => null)

  if (prompt === null) return

  decidingId.value = approval.approvalId
  try {
    await approvalApi.decide(approval.approvalId, {
      decision,
      comment: prompt.value || undefined
    })
    ElMessage.success(decision === 'APPROVED' ? '审批已通过' : '审批已驳回')
    await load()
  } catch {
    // 错误提示由 api 拦截器统一处理
  } finally {
    decidingId.value = null
  }
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
      title="审批中心"
      description="处理人工审批节点上的决策，决策后工作流将继续执行。"
    >
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <div class="fp-card fp-appr__toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="fp-appr__status">
        <el-option
          v-for="option in APPROVAL_STATUS_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <span class="fp-subtle">默认展示待审批任务</span>
    </div>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <div class="fp-card">
      <div class="fp-card__body fp-card__body--flush">
        <el-table v-loading="loading" :data="list" row-key="approvalId" style="width: 100%">
          <el-table-column label="审批事项" min-width="240">
            <template #default="{ row }: { row: Approval }">
              <div class="fp-appr__title">{{ row.title }}</div>
              <div v-if="row.description" class="fp-appr__desc fp-truncate">
                {{ row.description }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="170">
            <template #default="{ row }: { row: Approval }">
              <span class="fp-muted">{{ row.approvalType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="所属执行" width="120">
            <template #default="{ row }: { row: Approval }">
              <el-button
                size="small"
                text
                type="primary"
                @click="router.push({ name: 'execution-detail', params: { id: row.workflowExecutionId } })"
              >
                #{{ row.workflowExecutionId }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="申请时间" width="180">
            <template #default="{ row }: { row: Approval }">
              <span class="fp-muted">{{ formatDateTime(row.requestedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }: { row: Approval }">
              <StatusTag
                :label="approvalStatusMeta(row.status).label"
                :tone="approvalStatusMeta(row.status).tone"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="right">
            <template #default="{ row }: { row: Approval }">
              <template v-if="row.status === 'PENDING'">
                <el-button
                  size="small"
                  type="success"
                  plain
                  :loading="decidingId === row.approvalId"
                  @click="decide(row, 'APPROVED')"
                >
                  通过
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :loading="decidingId === row.approvalId"
                  @click="decide(row, 'REJECTED')"
                >
                  驳回
                </el-button>
              </template>
              <span v-else class="fp-subtle">{{ row.comment || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-if="!loading && !error && list.length === 0"
          icon="✓"
          title="没有待处理的审批"
          description="当工作流进入人工审批节点时，任务会出现在这里。"
        />
      </div>

      <div v-if="total > 0" class="fp-appr__pager">
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
.fp-appr__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}

.fp-appr__status {
  width: 160px;
}

.fp-appr__title {
  font-size: 13.5px;
  font-weight: 550;
}

.fp-appr__desc {
  margin-top: 2px;
  max-width: 460px;
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-appr__pager {
  display: flex;
  justify-content: flex-end;
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--fp-border);
}

@media (max-width: 768px) {
  .fp-appr__status {
    width: 100%;
  }
}
</style>
