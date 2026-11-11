import type { ExecutionEvent, JsonObject } from './types'

/**
 * 执行事件流（SSE）客户端。
 *
 * 设计要点：
 * 1. 使用浏览器原生 `EventSource`。原生 API 无法自定义请求头，因此 JWT 通过
 *    查询参数 `?token=` 传递（后端契约允许）。
 * 2. 原生 `EventSource` 的 `onmessage` 只能接收默认事件类型，因此对每个已知的
 *    事件类型使用 `addEventListener` 注册监听器，并在回调中自行解析
 *    `id:`（event.lastEventId）、`event:`（event.type）、`data:`（event.data）。
 * 3. 断线重连采用指数退避；重连时携带最后接收到的序号，服务端据此补发事件
 *    （后端同时支持 `Last-Event-ID` 请求头与 `?lastEventId=` 查询参数）。
 */

export type SseStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface SseEvent {
  /** 事件序号（来自 `id:` 行或负载中的 sequenceNo） */
  id: number | null
  /** 事件类型（来自 `event:` 行） */
  type: string
  /** 解析后的 JSON 负载 */
  data: JsonObject | null
  /** 原始负载文本 */
  raw: string
}

export interface SseClientOptions {
  /** 不含 token 的完整地址 */
  url: string
  /** JWT，用于查询参数鉴权 */
  token: string
  /** 初始续传位点 */
  lastEventId?: number | null
  /** 已知的自定义事件类型 */
  eventTypes?: readonly string[]
  onEvent: (event: SseEvent) => void
  onStatus?: (status: SseStatus) => void
  onError?: (error: Error) => void
  /** 断线重连成功后触发，可用于通过 REST 补齐遗漏事件 */
  onResumed?: (lastEventId: number | null) => void
}

/**
 * 已知的自定义事件类型。
 *
 * 原生 `EventSource` 只会把 `event:` 行声明的类型派发给同名监听器，
 * 因此这里必须枚举后端会发出的全部事件类型；新增后端事件时在此追加即可。
 * 未声明 `event:` 的消息由默认的 `message` 监听器兜底。
 */
const DEFAULT_EVENT_TYPES = [
  // 工作流级
  'WORKFLOW_CREATED',
  'WORKFLOW_STARTED',
  'WORKFLOW_RESUMED',
  'WORKFLOW_RETRY_WAIT',
  'WORKFLOW_WAITING_HUMAN',
  'WORKFLOW_SUCCEEDED',
  'WORKFLOW_FAILED',
  'WORKFLOW_CANCELED',
  // 节点级
  'NODE_READY',
  'NODE_STARTED',
  'NODE_SUCCEEDED',
  'NODE_FAILED',
  'NODE_RETRY',
  'NODE_SKIPPED',
  'NODE_CANCELED',
  'NODE_WAITING_HUMAN',
  'NODE_TIMED_OUT',
  // Agent 级
  'AGENT_STARTED',
  'AGENT_INTERRUPTED',
  'AGENT_COMPLETED',
  'AGENT_FAILED',
  'AGENT_RETRY',
  // 审批 / 报告
  'APPROVAL_SUBMITTED',
  'APPROVAL_DECIDED',
  'REPORT_GENERATED',
  'REPORT_CREATED',
  // 执行级 / 心跳
  'EXECUTION_STARTED',
  'EXECUTION_COMPLETED',
  'HEARTBEAT',
  'PING'
] as const

const BASE_DELAY = 1_000
const MAX_DELAY = 15_000

function buildUrl(url: string, token: string, lastEventId: number | null): string {
  const separator = url.includes('?') ? '&' : '?'
  let result = `${url}${separator}token=${encodeURIComponent(token)}`
  if (lastEventId !== null && lastEventId > 0) {
    result += `&lastEventId=${lastEventId}`
  }
  return result
}

function parsePayload(raw: string): JsonObject | null {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return parsed as JsonObject
    }
    return null
  } catch {
    return null
  }
}

export class SseClient {
  private source: EventSource | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private retryCount = 0
  private lastEventId: number | null
  private closed = false

  constructor(private readonly options: SseClientOptions) {
    this.lastEventId = options.lastEventId ?? null
  }

  /** 当前已接收到的最后序号 */
  get lastSequence(): number | null {
    return this.lastEventId
  }

  connect(): void {
    if (this.closed) return
    this.cleanupSource()
    this.options.onStatus?.(this.retryCount === 0 ? 'connecting' : 'reconnecting')

    const source = new EventSource(buildUrl(this.options.url, this.options.token, this.lastEventId))
    this.source = source

    source.onopen = () => {
      const resumed = this.retryCount > 0
      this.retryCount = 0
      this.options.onStatus?.('open')
      if (resumed) {
        this.options.onResumed?.(this.lastEventId)
      }
    }

    source.onerror = () => {
      if (this.closed) return
      // 关闭当前连接，交给自带的退避重连逻辑，避免浏览器原生重连造成双连接
      this.cleanupSource()
      this.options.onStatus?.('reconnecting')
      this.options.onError?.(new Error('SSE 连接中断，正在重连'))
      this.scheduleReconnect()
    }

    const handler = (event: MessageEvent<string>) => {
      this.handleEvent(event.type || 'message', event)
    }

    // 默认事件类型（未声明 `event:` 的消息）
    source.addEventListener('message', handler)
    for (const type of this.options.eventTypes ?? DEFAULT_EVENT_TYPES) {
      source.addEventListener(type, handler)
    }
  }

  close(): void {
    this.closed = true
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
    this.cleanupSource()
    this.options.onStatus?.('closed')
  }

  private handleEvent(type: string, event: MessageEvent<string>): void {
    const raw = typeof event.data === 'string' ? event.data : String(event.data ?? '')
    const data = parsePayload(raw)

    let id: number | null = null
    if (event.lastEventId) {
      const parsedId = Number(event.lastEventId)
      if (Number.isFinite(parsedId)) id = parsedId
    }
    if (id === null && data) {
      const seq = data.sequenceNo
      if (typeof seq === 'number' && Number.isFinite(seq)) id = seq
    }
    if (id !== null && (this.lastEventId === null || id > this.lastEventId)) {
      this.lastEventId = id
    }

    this.options.onEvent({ id, type, data, raw })
  }

  private scheduleReconnect(): void {
    if (this.closed) return
    if (this.timer !== null) clearTimeout(this.timer)

    const delay = Math.min(BASE_DELAY * 2 ** this.retryCount, MAX_DELAY)
    this.retryCount += 1

    this.timer = setTimeout(() => {
      this.timer = null
      if (!this.closed) this.connect()
    }, delay)
  }

  private cleanupSource(): void {
    if (!this.source) return
    this.source.close()
    this.source = null
  }
}

/** 把 SSE 事件负载规整为契约中的 ExecutionEvent 结构 */
export function toExecutionEvent(event: SseEvent): ExecutionEvent | null {
  const payload = event.data
  if (!payload) return null

  const sequenceNo =
    typeof payload.sequenceNo === 'number'
      ? payload.sequenceNo
      : (event.id ?? Number.NaN)
  if (!Number.isFinite(sequenceNo)) return null

  const eventType =
    typeof payload.eventType === 'string' && payload.eventType
      ? payload.eventType
      : event.type

  const eventData =
    payload.eventData && typeof payload.eventData === 'object'
      ? (payload.eventData as JsonObject)
      : (payload as JsonObject)

  return {
    sequenceNo,
    eventType,
    nodeKey: typeof payload.nodeKey === 'string' ? payload.nodeKey : null,
    status: typeof payload.status === 'string' ? payload.status : null,
    eventData,
    createdAt: typeof payload.createdAt === 'string' ? payload.createdAt : null
  }
}
