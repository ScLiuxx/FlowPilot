<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { reportApi } from '@/api/client'
import type { Id, Report } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { reportStatusMeta, REPORT_STATUS_OPTIONS } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

const list = ref<Report[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const downloadingId = ref<Id | null>(null)

const query = reactive({
  page: 1,
  size: 10,
  status: ''
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await reportApi.list({
      page: query.page,
      size: query.size,
      status: query.status || undefined
    })
    list.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch {
    error.value = '报告列表加载失败，请检查后端服务是否已启动'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function download(report: Report): Promise<void> {
  downloadingId.value = report.reportId
  try {
    const blob = await reportApi.download(report.reportId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${report.reportNo}.md`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('报告已下载')
  } catch {
    ElMessage.error('报告下载失败')
  } finally {
    downloadingId.value = null
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
      title="报告"
      description="查看工作流产出的结构化报告，支持导出 Markdown。"
    >
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <div class="fp-card fp-rpt__toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="fp-rpt__status">
        <el-option
          v-for="option in REPORT_STATUS_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <span class="fp-subtle">共 {{ total }} 份报告</span>
    </div>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <div class="fp-card">
      <div class="fp-card__body fp-card__body--flush">
        <el-table v-loading="loading" :data="list" row-key="reportId" style="width: 100%">
          <el-table-column label="报告" min-width="260">
            <template #default="{ row }: { row: Report }">
              <div class="fp-rpt__title">{{ row.title }}</div>
              <div class="fp-rpt__no">{{ row.reportNo }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="180">
            <template #default="{ row }: { row: Report }">
              <span class="fp-muted">{{ row.reportType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="所属执行" width="120">
            <template #default="{ row }: { row: Report }">
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
          <el-table-column label="状态" width="110">
            <template #default="{ row }: { row: Report }">
              <StatusTag
                :label="reportStatusMeta(row.status).label"
                :tone="reportStatusMeta(row.status).tone"
              />
            </template>
          </el-table-column>
          <el-table-column label="生成时间" width="180">
            <template #default="{ row }: { row: Report }">
              <span class="fp-muted">{{ formatDateTime(row.generatedAt || row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }: { row: Report }">
              <el-button
                size="small"
                text
                type="primary"
                @click="router.push({ name: 'report-detail', params: { id: row.reportId } })"
              >
                查看
              </el-button>
              <el-button
                size="small"
                text
                :icon="Download"
                :loading="downloadingId === row.reportId"
                @click="download(row)"
              >
                Markdown
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-if="!loading && !error && list.length === 0"
          icon="▤"
          title="暂无报告"
          description="工作流生成报告后，会在这里统一归档。"
        />
      </div>

      <div v-if="total > 0" class="fp-rpt__pager">
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
.fp-rpt__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}

.fp-rpt__status {
  width: 170px;
}

.fp-rpt__title {
  font-size: 13.5px;
  font-weight: 550;
}

.fp-rpt__no {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-rpt__pager {
  display: flex;
  justify-content: flex-end;
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--fp-border);
}

@media (max-width: 768px) {
  .fp-rpt__status {
    width: 100%;
  }
}
</style>
