import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'
import { ElMessage } from 'element-plus'
import { pinia } from '@/stores'
import { useAuthStore } from '@/stores/auth'
import type {
  ApiEnvelope,
  Approval,
  ApprovalDecisionRequest,
  ApprovalDecisionResult,
  DagValidationResult,
  Execution,
  ExecutionCancelRequest,
  ExecutionCancelResult,
  ExecutionEvent,
  ExecutionStartRequest,
  ExecutionStartResult,
  LoginRequest,
  LoginResult,
  MonitoringOverview,
  NodeAttempt,
  NodeExecution,
  PageResult,
  RegisterRequest,
  Report,
  Workflow,
  WorkflowCreateRequest,
  WorkflowDefinition,
  WorkflowPublishRequest,
  WorkflowUpdateRequest,
  WorkflowVersion,
  WorkflowVersionCreateRequest
} from './types'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 为 true 时该请求失败不弹 toast（用于后台探测类请求） */
    silent?: boolean
  }
}

/** 业务错误（信封 code !== 0） */
export class ApiError extends Error {
  readonly code: number
  readonly requestId?: string

  constructor(code: number, message: string, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.requestId = requestId
  }
}

/** 扩展请求配置：silent 表示该请求失败时不弹 toast（用于后台探测类请求） */
export type RequestOptions = AxiosRequestConfig

/** 未授权回调（由 main.ts 注册，避免 api 层直接依赖 router） */
type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

function isEnvelope(body: unknown): body is ApiEnvelope<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as { code?: unknown }).code === 'number' &&
    typeof (body as { message?: unknown }).message === 'string'
  )
}

export const http: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20_000
})

/* ---------------------------------------------------------------------- *
 * 请求拦截：附加 Authorization: Bearer <token>
 * 令牌来源于 Pinia auth store（惰性读取，避免模块初始化期的循环依赖）
 * ---------------------------------------------------------------------- */
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore(pinia)
  if (auth.token) {
    config.headers.set('Authorization', `Bearer ${auth.token}`)
  }
  return config
})

/* ---------------------------------------------------------------------- *
 * 响应拦截：拆信封 + 错误提示
 * ---------------------------------------------------------------------- */
http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body: unknown = response.data
    if (isEnvelope(body)) {
      if (body.code !== 0) {
        if (!response.config.silent) {
          ElMessage.error(body.message || '请求失败')
        }
        throw new ApiError(body.code, body.message, body.requestId)
      }
      // 解包后的载荷即为业务数据
      response.data = body.data
    }
    return response
  },
  (error: AxiosError<unknown>) => {
    const status = error.response?.status
    const body: unknown = error.response?.data
    const silent = Boolean(error.config?.silent)

    if (status === 401) {
      unauthorizedHandler?.()
    }

    let message = '网络异常，请稍后重试'
    if (isEnvelope(body) && body.message) {
      message = body.message
    } else if (status === 403) {
      message = '没有权限执行该操作'
    } else if (status === 404) {
      message = '请求的资源不存在'
    } else if (status && status >= 500) {
      message = '服务暂时不可用，请稍后重试'
    } else if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    }

    if (status !== 401 && !silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(status ?? -1, message))
  }
)

/* ---------------------------------------------------------------------- *
 * 通用请求封装
 * ---------------------------------------------------------------------- */

async function request<T>(config: RequestOptions): Promise<T> {
  const response = await http.request<T>(config)
  return response.data
}

function toQuery(params: Record<string, unknown> | undefined): Record<string, unknown> {
  const query: Record<string, unknown> = {}
  if (!params) return query
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    query[key] = value
  }
  return query
}

/* ---------------------------------------------------------------------- *
 * 认证
 * ---------------------------------------------------------------------- */
export const authApi = {
  login: (payload: LoginRequest) => request<LoginResult>({ url: '/auth/login', method: 'POST', data: payload }),
  register: (payload: RegisterRequest) =>
    request<unknown>({ url: '/auth/register', method: 'POST', data: payload }),
  /** 后台刷新用户信息：失败时不弹 toast，交由 401 处理器处理会话失效 */
  me: () => request<LoginResult['user']>({ url: '/auth/me', method: 'GET', silent: true })
}

/* ---------------------------------------------------------------------- *
 * 工作流
 * ---------------------------------------------------------------------- */
export const workflowApi = {
  list: (params?: { page?: number; size?: number; status?: string; keyword?: string }) =>
    request<PageResult<Workflow>>({ url: '/workflows', method: 'GET', params: toQuery(params) }),
  detail: (workflowId: number) => request<Workflow>({ url: `/workflows/${workflowId}`, method: 'GET' }),
  create: (payload: WorkflowCreateRequest) =>
    request<Workflow>({ url: '/workflows', method: 'POST', data: payload }),
  update: (workflowId: number, payload: WorkflowUpdateRequest) =>
    request<Workflow>({ url: `/workflows/${workflowId}`, method: 'PUT', data: payload }),
  remove: (workflowId: number) => request<unknown>({ url: `/workflows/${workflowId}`, method: 'DELETE' }),
  versions: (workflowId: number) =>
    request<WorkflowVersion[]>({ url: `/workflows/${workflowId}/versions`, method: 'GET' }),
  version: (workflowId: number, versionId: number) =>
    request<WorkflowVersion>({ url: `/workflows/${workflowId}/versions/${versionId}`, method: 'GET' }),
  createVersion: (workflowId: number, payload: WorkflowVersionCreateRequest) =>
    request<WorkflowVersion>({ url: `/workflows/${workflowId}/versions`, method: 'POST', data: payload }),
  publish: (workflowId: number, payload: WorkflowPublishRequest) =>
    request<unknown>({ url: `/workflows/${workflowId}/publish`, method: 'POST', data: payload }),
  validate: (definition: WorkflowDefinition) =>
    request<DagValidationResult>({ url: '/workflows/validate', method: 'POST', data: { definition } }),
  /** 启动一次执行（异步运行，立即返回） */
  startExecution: (workflowId: number, payload: ExecutionStartRequest) =>
    request<ExecutionStartResult>({
      url: `/workflows/${workflowId}/executions`,
      method: 'POST',
      data: payload
    })
}

/* ---------------------------------------------------------------------- *
 * 执行
 * ---------------------------------------------------------------------- */
export const executionApi = {
  list: (params?: { page?: number; size?: number; status?: string; workflowId?: number }) =>
    request<PageResult<Execution>>({ url: '/executions', method: 'GET', params: toQuery(params) }),
  detail: (executionId: number) => request<Execution>({ url: `/executions/${executionId}`, method: 'GET' }),
  nodes: (executionId: number) =>
    request<NodeExecution[]>({ url: `/executions/${executionId}/nodes`, method: 'GET' }),
  attempts: (executionId: number, nodeExecutionId: number) =>
    request<NodeAttempt[]>({
      url: `/executions/${executionId}/nodes/${nodeExecutionId}/attempts`,
      method: 'GET'
    }),
  events: (executionId: number, params?: { afterSequence?: number; limit?: number }) =>
    request<ExecutionEvent[]>({
      url: `/executions/${executionId}/events`,
      method: 'GET',
      params: toQuery(params)
    }),
  cancel: (executionId: number, payload: ExecutionCancelRequest) =>
    request<ExecutionCancelResult>({
      url: `/executions/${executionId}/cancel`,
      method: 'POST',
      data: payload
    })
}

/* ---------------------------------------------------------------------- *
 * 审批
 * ---------------------------------------------------------------------- */
export const approvalApi = {
  list: (params?: { page?: number; size?: number; status?: string }) =>
    request<PageResult<Approval>>({ url: '/approvals', method: 'GET', params: toQuery(params) }),
  detail: (approvalId: number) => request<Approval>({ url: `/approvals/${approvalId}`, method: 'GET' }),
  decide: (approvalId: number, payload: ApprovalDecisionRequest) =>
    request<ApprovalDecisionResult>({
      url: `/approvals/${approvalId}/decision`,
      method: 'POST',
      data: payload
    })
}

/* ---------------------------------------------------------------------- *
 * 报告
 * ---------------------------------------------------------------------- */
export const reportApi = {
  list: (params?: { page?: number; size?: number; status?: string }) =>
    request<PageResult<Report>>({ url: '/reports', method: 'GET', params: toQuery(params) }),
  detail: (reportId: number) => request<Report>({ url: `/reports/${reportId}`, method: 'GET' }),
  /** 下载 Markdown 原文（返回 text/markdown 附件，不走信封） */
  download: (reportId: number) =>
    request<Blob>({ url: `/reports/${reportId}/download`, method: 'GET', responseType: 'blob' })
}

/* ---------------------------------------------------------------------- *
 * 监控
 * ---------------------------------------------------------------------- */
export const monitoringApi = {
  overview: () => request<MonitoringOverview>({ url: '/monitoring/overview', method: 'GET' })
}
