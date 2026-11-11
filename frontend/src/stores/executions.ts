import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { executionApi } from '@/api/client'
import { SseClient, toExecutionEvent, type SseStatus } from '@/api/sse'
import type { Execution, ExecutionEvent, NodeAttempt, NodeExecution } from '@/api/types'

export const useExecutionStore = defineStore('executions', () => {
  const current = ref<Execution | null>(null)
  const nodes = ref<NodeExecution[]>([])
  const attemptsByNode = ref<Record<number, NodeAttempt[]>>({})
  const events = ref<ExecutionEvent[]>([])
  const streamStatus = ref<SseStatus>('idle')
  const loading = ref(false)
  const error = ref<string>('')

  let client: SseClient | null = null
  let connectedExecutionId: number | null = null

  const isStreaming = computed(() => streamStatus.value === 'open')
  const isReconnecting = computed(
    () => streamStatus.value === 'reconnecting' || streamStatus.value === 'connecting'
  )

  function reset(): void {
    disconnectStream()
    current.value = null
    nodes.value = []
    attemptsByNode.value = {}
    events.value = []
    error.value = ''
    streamStatus.value = 'idle'
  }

  async function loadDetail(executionId: number): Promise<Execution | null> {
    loading.value = true
    error.value = ''
    try {
      const detail = await executionApi.detail(executionId)
      current.value = detail
      return detail
    } catch {
      error.value = '无法加载执行详情'
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadNodes(executionId: number): Promise<void> {
    try {
      nodes.value = await executionApi.nodes(executionId)
    } catch {
      error.value = '无法加载节点执行列表'
    }
  }

  async function loadAttempts(executionId: number, nodeExecutionId: number): Promise<void> {
    try {
      const list = await executionApi.attempts(executionId, nodeExecutionId)
      attemptsByNode.value = { ...attemptsByNode.value, [nodeExecutionId]: list }
    } catch {
      error.value = '无法加载节点重试记录'
    }
  }

  /** 加载历史事件（按序号去重合并） */
  async function loadHistory(executionId: number): Promise<void> {
    await mergeEvents(executionId, 0)
  }

  /** 增量拉取指定序号之后的事件，用于断线续传后的补齐 */
  async function mergeEvents(executionId: number, afterSequence: number): Promise<void> {
    try {
      const history = await executionApi.events(executionId, { afterSequence, limit: 200 })
      const known = new Set(events.value.map((event) => event.sequenceNo))
      const merged = [...events.value]
      for (const event of history) {
        if (!known.has(event.sequenceNo)) {
          known.add(event.sequenceNo)
          merged.push(event)
        }
      }
      merged.sort((a, b) => a.sequenceNo - b.sequenceNo)
      events.value = merged
    } catch {
      error.value = '无法加载历史事件'
    }
  }

  function appendEvent(event: ExecutionEvent): void {
    if (events.value.some((existing) => existing.sequenceNo === event.sequenceNo)) return
    events.value = [...events.value, event].sort((a, b) => a.sequenceNo - b.sequenceNo)
  }

  /** 建立 SSE 实时连接（同一执行重复调用时保持现有连接，避免重复订阅） */
  function connectStream(executionId: number, token: string): void {
    if (client && connectedExecutionId === executionId) return
    disconnectStream()
    connectedExecutionId = executionId
    const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
    client = new SseClient({
      url: `${base}/executions/${executionId}/events/stream`,
      token,
      lastEventId: events.value.length
        ? events.value[events.value.length - 1]!.sequenceNo
        : null,
      onEvent: (raw) => {
        const normalized = toExecutionEvent(raw)
        if (normalized) appendEvent(normalized)
      },
      onStatus: (status) => {
        streamStatus.value = status
      },
      onResumed: (lastEventId) => {
        // 重连成功后通过 REST 补齐断线期间遗漏的事件
        void mergeEvents(executionId, lastEventId ?? 0)
      }
    })
    client.connect()
  }

  function disconnectStream(): void {
    client?.close()
    client = null
    connectedExecutionId = null
    if (streamStatus.value !== 'closed') {
      streamStatus.value = 'idle'
    }
  }

  async function cancel(executionId: number, reason = 'USER_REQUEST'): Promise<boolean> {
    try {
      await executionApi.cancel(executionId, { reason })
      await loadDetail(executionId)
      await loadNodes(executionId)
      return true
    } catch {
      error.value = '取消执行失败'
      return false
    }
  }

  return {
    current,
    nodes,
    attemptsByNode,
    events,
    streamStatus,
    loading,
    error,
    isStreaming,
    isReconnecting,
    reset,
    loadDetail,
    loadNodes,
    loadAttempts,
    loadHistory,
    appendEvent,
    connectStream,
    disconnectStream,
    cancel
  }
})
