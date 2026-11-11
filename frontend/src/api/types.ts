/* ==========================================================================
   FlowPilot REST 契约类型定义
   对应 /workspace/docs/api-contract.md
   ========================================================================== */

/** 统一响应信封 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T | null
  requestId?: string
  timestamp?: string
}

/** 分页结果 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

/** 动态 JSON 对象（事件负载、输入输出等） */
export type JsonObject = Record<string, unknown>

/* ---------------------------------------------------------------- 认证 --- */

/** 用户角色 */
export type UserRole = 'USER' | 'ADMIN' | string

export interface AuthUser {
  id: number
  username: string
  role: UserRole
  nickname?: string | null
  email?: string | null
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
  nickname?: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

/* -------------------------------------------------------------- 工作流 --- */

/** 工作流定义状态 */
export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

/** 工作流版本状态 */
export type WorkflowVersionStatus = 'DRAFT' | 'PUBLISHED'

/** 节点类型 */
export type WorkflowNodeType = 'HTTP' | 'CODE' | 'CONDITION' | 'LLM' | 'AGENT' | 'HUMAN' | 'MERGE'

export interface RetryPolicy {
  maxAttempts?: number
  initialDelaySeconds?: number
  maxDelaySeconds?: number
  multiplier?: number
}

export interface TimeoutPolicy {
  timeoutSeconds?: number
}

/** DAG 节点定义 */
export interface WorkflowNode {
  id: string
  name?: string
  type: WorkflowNodeType
  dependencies: string[]
  config?: JsonObject
  retryPolicy?: RetryPolicy
  timeoutPolicy?: TimeoutPolicy
}

/** 工作流 DAG 定义 */
export interface WorkflowDefinition {
  nodes: WorkflowNode[]
}

/** 工作流（列表 / 详情） */
export interface Workflow {
  id: number
  name: string
  description?: string | null
  category?: string | null
  status?: WorkflowStatus
  latestVersion?: number
  ownerId?: number
  definition?: WorkflowDefinition | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface WorkflowCreateRequest {
  name: string
  description?: string
  category?: string
  definition: WorkflowDefinition
}

export interface WorkflowUpdateRequest {
  name?: string
  description?: string
  category?: string
}

export interface WorkflowVersion {
  id: number
  workflowId: number
  version: number
  status: WorkflowVersionStatus
  definitionHash?: string | null
  definition?: WorkflowDefinition | null
  createdBy?: number | null
  publishedAt?: string | null
  createdAt?: string | null
}

export interface WorkflowVersionCreateRequest {
  definition: WorkflowDefinition
}

export interface WorkflowPublishRequest {
  versionId: number
}

/** DAG 校验结果 */
export interface DagValidationResult {
  valid: boolean
  topologicalOrder: string[]
  errors: string[]
  warnings: string[]
}

/* -------------------------------------------------------------- 执行 --- */

/** 执行状态 */
export type ExecutionStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING_HUMAN'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'

/** 节点执行状态 */
export type NodeExecutionStatus =
  | 'PENDING'
  | 'READY'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'WAITING_HUMAN'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELED'

export interface Execution {
  executionId: number
  executionNo: string
  workflowId: number
  workflowVersionId: number
  status: ExecutionStatus
  totalNodes: number
  completedNodes: number
  failedNodes: number
  input?: JsonObject | null
  output?: JsonObject | null
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
}

export interface NodeExecution {
  nodeExecutionId: number
  nodeKey: string
  nodeType: string
  status: NodeExecutionStatus
  attempt: number
  maxAttempts: number
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  errorCode?: string | null
  errorMessage?: string | null
}

export interface NodeAttempt {
  attemptNo: number
  status: NodeExecutionStatus | string
  workerId?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
}

/** 执行事件（历史接口与 SSE 共用） */
export interface ExecutionEvent {
  sequenceNo: number
  eventType: string
  nodeKey?: string | null
  status?: string | null
  eventData?: JsonObject | null
  createdAt?: string | null
}

export interface ExecutionStartRequest {
  workflowVersionId?: number
  input?: JsonObject
  idempotencyKey?: string
}

export interface ExecutionStartResult {
  executionId: number
  executionNo: string
  status: ExecutionStatus
}

export interface ExecutionCancelRequest {
  reason?: string
}

export interface ExecutionCancelResult {
  executionId: number
  status: string
}

/* -------------------------------------------------------------- 审批 --- */

/** 审批状态 */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface Approval {
  approvalId: number
  workflowExecutionId: number
  nodeExecutionId?: number | null
  approvalType: string
  status: ApprovalStatus
  title: string
  description?: string | null
  requestData?: JsonObject | null
  decisionData?: JsonObject | null
  requestedAt?: string | null
  decidedAt?: string | null
  approverId?: number | null
  comment?: string | null
}

export type ApprovalDecision = 'APPROVED' | 'REJECTED'

export interface ApprovalDecisionRequest {
  decision: ApprovalDecision
  comment?: string
}

export interface ApprovalDecisionResult {
  approvalId: number
  status: ApprovalStatus
}

/* -------------------------------------------------------------- 报告 --- */

/** 报告状态 */
export type ReportStatus =
  | 'GENERATING'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'PUBLISHED'
  | 'FAILED'

/** 报告结构化内容（章节形状由后端决定，前端做容错渲染） */
export interface ReportContent extends JsonObject {
  sections?: unknown
}

/** 报告 */
export interface Report {
  reportId: number
  reportNo: string
  workflowExecutionId: number
  title: string
  repositoryUrl?: string | null
  reportType: string
  status: ReportStatus
  summary?: string | null
  content?: ReportContent | null
  markdownContent?: string | null
  contentHash?: string | null
  generatedAt?: string | null
  createdAt?: string | null
}

/* -------------------------------------------------------------- 监控 --- */

export interface MonitoringOverview {
  totalExecutions: number
  runningExecutions: number
  failedExecutions: number
  waitingHumanExecutions: number
  succeededExecutions: number
  pendingApprovals: number
  totalNodes: number
  totalTokenInput: number
  totalTokenOutput: number
}
