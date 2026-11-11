import type {
  ApprovalStatus,
  ExecutionStatus,
  NodeExecutionStatus,
  ReportStatus,
  WorkflowStatus
} from '@/api/types'

export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'running'

export interface StatusMeta {
  label: string
  tone: StatusTone
}

const EXECUTION_STATUS_META: Record<ExecutionStatus, StatusMeta> = {
  CREATED: { label: '已创建', tone: 'neutral' },
  QUEUED: { label: '排队中', tone: 'info' },
  RUNNING: { label: '执行中', tone: 'running' },
  WAITING_HUMAN: { label: '等待人工', tone: 'warning' },
  RETRY_WAIT: { label: '等待重试', tone: 'warning' },
  SUCCEEDED: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  CANCELED: { label: '已取消', tone: 'neutral' }
}

const NODE_STATUS_META: Record<NodeExecutionStatus, StatusMeta> = {
  PENDING: { label: '待调度', tone: 'neutral' },
  READY: { label: '就绪', tone: 'info' },
  RUNNING: { label: '执行中', tone: 'running' },
  RETRY_WAIT: { label: '等待重试', tone: 'warning' },
  WAITING_HUMAN: { label: '等待人工', tone: 'warning' },
  SUCCEEDED: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  SKIPPED: { label: '已跳过', tone: 'neutral' },
  CANCELED: { label: '已取消', tone: 'neutral' }
}

const WORKFLOW_STATUS_META: Record<WorkflowStatus, StatusMeta> = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  PUBLISHED: { label: '已发布', tone: 'success' },
  DISABLED: { label: '已禁用', tone: 'danger' }
}

const APPROVAL_STATUS_META: Record<ApprovalStatus, StatusMeta> = {
  PENDING: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已通过', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' }
}

const REPORT_STATUS_META: Record<ReportStatus, StatusMeta> = {
  GENERATING: { label: '生成中', tone: 'running' },
  PENDING_REVIEW: { label: '待审核', tone: 'warning' },
  APPROVED: { label: '已通过', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  PUBLISHED: { label: '已发布', tone: 'info' },
  FAILED: { label: '生成失败', tone: 'danger' }
}

export function executionStatusMeta(status?: string | null): StatusMeta {
  if (!status) return { label: '未知', tone: 'neutral' }
  return EXECUTION_STATUS_META[status as ExecutionStatus] ?? { label: status, tone: 'neutral' }
}

export function nodeStatusMeta(status?: string | null): StatusMeta {
  if (!status) return { label: '未知', tone: 'neutral' }
  return NODE_STATUS_META[status as NodeExecutionStatus] ?? { label: status, tone: 'neutral' }
}

export function workflowStatusMeta(status?: string | null): StatusMeta {
  if (!status) return { label: '未知', tone: 'neutral' }
  return WORKFLOW_STATUS_META[status as WorkflowStatus] ?? { label: status, tone: 'neutral' }
}

export function approvalStatusMeta(status?: string | null): StatusMeta {
  if (!status) return { label: '未知', tone: 'neutral' }
  return APPROVAL_STATUS_META[status as ApprovalStatus] ?? { label: status, tone: 'neutral' }
}

export function reportStatusMeta(status?: string | null): StatusMeta {
  if (!status) return { label: '未知', tone: 'neutral' }
  return REPORT_STATUS_META[status as ReportStatus] ?? { label: status, tone: 'neutral' }
}

/** 执行状态筛选选项 */
export const EXECUTION_STATUS_OPTIONS = Object.entries(EXECUTION_STATUS_META).map(
  ([value, meta]) => ({ value, label: meta.label })
)

/** 终态：执行进入这些状态后不再需要实时事件流 */
export const TERMINAL_EXECUTION_STATUSES: readonly string[] = ['SUCCEEDED', 'FAILED', 'CANCELED']

/** 工作流状态筛选选项 */
export const WORKFLOW_STATUS_OPTIONS = Object.entries(WORKFLOW_STATUS_META).map(
  ([value, meta]) => ({ value, label: meta.label })
)

/** 审批状态筛选选项 */
export const APPROVAL_STATUS_OPTIONS = Object.entries(APPROVAL_STATUS_META).map(
  ([value, meta]) => ({ value, label: meta.label })
)

/** 报告状态筛选选项 */
export const REPORT_STATUS_OPTIONS = Object.entries(REPORT_STATUS_META).map(
  ([value, meta]) => ({ value, label: meta.label })
)

/** 节点类型中文说明 */
export const NODE_TYPE_LABELS: Record<string, string> = {
  HTTP: 'HTTP 请求',
  CODE: '代码执行',
  CONDITION: '条件分支',
  LLM: '模型调用',
  AGENT: 'Agent',
  HUMAN: '人工节点',
  MERGE: '结果汇聚'
}
