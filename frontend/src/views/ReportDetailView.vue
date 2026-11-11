<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Download } from '@element-plus/icons-vue'
import { reportApi } from '@/api/client'
import type { Report } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ErrorState from '@/components/ErrorState.vue'
import ReportContent from '@/components/ReportContent.vue'
import { reportStatusMeta } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const reportId = computed(() => String(route.params.id))

const report = ref<Report | null>(null)
const loading = ref(true)
const error = ref('')
const downloading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    report.value = await reportApi.detail(reportId.value)
  } catch {
    error.value = '报告详情加载失败，请检查后端服务是否已启动'
    report.value = null
  } finally {
    loading.value = false
  }
}

async function download(): Promise<void> {
  const current = report.value
  if (!current) return
  downloading.value = true
  try {
    const blob = await reportApi.download(current.reportId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${current.reportNo}.md`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('报告已下载')
  } catch {
    ElMessage.error('报告下载失败')
  } finally {
    downloading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="fp-page">
    <el-button text :icon="ArrowLeft" class="fp-rptd__back" @click="router.back()">返回</el-button>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <template v-else>
      <PageHeader :title="report?.title ?? '报告详情'" :description="report?.summary || ''">
        <StatusTag
          v-if="report"
          :label="reportStatusMeta(report.status).label"
          :tone="reportStatusMeta(report.status).tone"
        />
        <el-button :icon="Download" :loading="downloading" @click="download">下载 Markdown</el-button>
      </PageHeader>

      <div v-if="loading" class="fp-card">
        <div class="fp-card__body">
          <el-skeleton animated :rows="8" />
        </div>
      </div>

      <div v-else-if="report" class="fp-stack">
        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">报告信息</h2>
          </div>
          <div class="fp-card__body">
            <dl class="fp-rptd__meta">
              <div class="fp-rptd__meta-item">
                <dt>报告编号</dt>
                <dd class="fp-mono">{{ report.reportNo }}</dd>
              </div>
              <div class="fp-rptd__meta-item">
                <dt>报告类型</dt>
                <dd>{{ report.reportType }}</dd>
              </div>
              <div class="fp-rptd__meta-item">
                <dt>所属执行</dt>
                <dd>
                  <el-button
                    text
                    type="primary"
                    @click="router.push({ name: 'execution-detail', params: { id: report.workflowExecutionId } })"
                  >
                    #{{ report.workflowExecutionId }}
                  </el-button>
                </dd>
              </div>
              <div class="fp-rptd__meta-item">
                <dt>生成时间</dt>
                <dd>{{ formatDateTime(report.generatedAt || report.createdAt) }}</dd>
              </div>
              <div class="fp-rptd__meta-item">
                <dt>仓库地址</dt>
                <dd class="fp-truncate">{{ report.repositoryUrl || '—' }}</dd>
              </div>
              <div class="fp-rptd__meta-item">
                <dt>内容哈希</dt>
                <dd class="fp-mono fp-subtle">{{ report.contentHash || '—' }}</dd>
              </div>
            </dl>
          </div>
        </div>

        <div class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">报告内容</h2>
          </div>
          <div class="fp-card__body">
            <ReportContent :content="report.content" />
          </div>
        </div>

        <div v-if="report.markdownContent" class="fp-card">
          <div class="fp-card__head">
            <h2 class="fp-section-title">Markdown 原文</h2>
            <el-button size="small" text :icon="Download" @click="download">下载</el-button>
          </div>
          <div class="fp-card__body">
            <pre class="fp-rptd__markdown">{{ report.markdownContent }}</pre>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fp-rptd__back {
  margin-bottom: var(--sp-2);
  margin-left: -8px;
}

.fp-rptd__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
  margin: 0;
}

.fp-rptd__meta-item {
  min-width: 0;
}

.fp-rptd__meta-item dt {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--fp-text-subtle);
}

.fp-rptd__meta-item dd {
  margin: 4px 0 0;
  font-size: 13.5px;
}

.fp-rptd__markdown {
  margin: 0;
  max-height: 460px;
  overflow: auto;
  padding: var(--sp-4);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-surface-3);
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 900px) {
  .fp-rptd__meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .fp-rptd__meta {
    grid-template-columns: 1fr;
  }
}
</style>
