import { fileURLToPath, URL } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期 Mock 服务。
 *
 * 后端尚未就绪时，可用 `npm run dev:mock` 启动一个内置的假后端，
 * 以便在浏览器中验证控制台各页面（登录、列表、详情、SSE 事件流）。
 * 生产构建不包含此插件。
 */
function mockBackend(): Plugin {
  return {
    name: 'flowpilot-mock-backend',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? ''
        if (!url.startsWith('/api/')) {
          next()
          return
        }
        const method = req.method ?? 'GET'
        const path = url.split('?')[0] ?? ''

        const send = (code: number, body: unknown): void => {
          res.statusCode = code
          res.setHeader('Content-Type', 'application/json; charset=utf-8')
          res.end(
            JSON.stringify({
              code: 0,
              message: 'success',
              data: body,
              requestId: 'mock-req-001',
              timestamp: new Date().toISOString()
            })
          )
        }

        // ---- auth ----
        if (method === 'POST' && path === '/api/v1/auth/login') {
          send(200, {
            token: 'mock.jwt.token',
            tokenType: 'Bearer',
            expiresIn: 7200,
            user: { id: 1, username: 'demo', role: 'ADMIN', nickname: '演示用户' }
          })
          return
        }
        if (method === 'POST' && path === '/api/v1/auth/register') {
          send(200, { id: 2, username: 'demo', role: 'USER', nickname: '演示用户' })
          return
        }
        if (method === 'GET' && path === '/api/v1/auth/me') {
          send(200, { id: 1, username: 'demo', role: 'ADMIN', nickname: '演示用户' })
          return
        }

        // ---- monitoring ----
        if (method === 'GET' && path === '/api/v1/monitoring/overview') {
          send(200, {
            totalExecutions: 42,
            runningExecutions: 3,
            failedExecutions: 2,
            waitingHumanExecutions: 1,
            succeededExecutions: 36,
            pendingApprovals: 2,
            totalNodes: 260,
            totalTokenInput: 120000,
            totalTokenOutput: 45000
          })
          return
        }

        // ---- workflows ----
        if (method === 'GET' && path === '/api/v1/workflows') {
          send(200, {
            total: 2,
            page: 1,
            size: 10,
            records: [
              {
                id: 100,
                name: 'GitHub 技术尽调',
                description: '开源项目技术尽调，产出结构化报告',
                category: 'GITHUB_DUE_DILIGENCE',
                status: 'PUBLISHED',
                latestVersion: 3,
                createdAt: '2026-11-01T02:00:00.000Z',
                updatedAt: '2026-11-10T08:30:00.000Z'
              },
              {
                id: 101,
                name: '变更影响分析',
                description: '针对单仓库的提交影响面分析',
                category: 'CHANGE_IMPACT',
                status: 'DRAFT',
                latestVersion: 1,
                createdAt: '2026-11-05T06:10:00.000Z',
                updatedAt: '2026-11-09T11:00:00.000Z'
              }
            ]
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/workflows/100') {
          send(200, {
            id: 100,
            name: 'GitHub 技术尽调',
            description: '开源项目技术尽调，产出结构化报告',
            category: 'GITHUB_DUE_DILIGENCE',
            status: 'PUBLISHED',
            latestVersion: 3,
            definition: {
              nodes: [
                {
                  id: 'collect_repository',
                  name: '采集仓库信息',
                  type: 'HTTP',
                  dependencies: [],
                  config: { operation: 'get_repository' }
                },
                {
                  id: 'analyze_repository',
                  name: '分析仓库',
                  type: 'AGENT',
                  dependencies: ['collect_repository'],
                  config: { agentType: 'REPOSITORY_ANALYST' }
                },
                {
                  id: 'review_report',
                  name: '报告审核',
                  type: 'HUMAN',
                  dependencies: ['analyze_repository'],
                  config: { approvalType: 'REPORT_REVIEW' }
                }
              ]
            },
            createdAt: '2026-11-01T02:00:00.000Z',
            updatedAt: '2026-11-10T08:30:00.000Z'
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/workflows/100/versions') {
          send(200, [
            {
              id: 200,
              workflowId: 100,
              version: 3,
              status: 'PUBLISHED',
              definitionHash: 'a3f1c2d4e5b6',
              publishedAt: '2026-11-10T08:30:00.000Z',
              createdAt: '2026-11-09T08:30:00.000Z'
            },
            {
              id: 201,
              workflowId: 100,
              version: 2,
              status: 'DRAFT',
              definitionHash: 'b7d2e1a4c9f0',
              publishedAt: null,
              createdAt: '2026-11-06T04:00:00.000Z'
            }
          ])
          return
        }
        if (method === 'POST' && path === '/api/v1/workflows/validate') {
          send(200, {
            valid: true,
            topologicalOrder: ['collect_repository', 'analyze_repository', 'review_report'],
            errors: [],
            warnings: ['review_report 节点缺少 timeoutPolicy 配置']
          })
          return
        }

        // ---- executions ----
        if (method === 'GET' && path === '/api/v1/executions') {
          send(200, {
            total: 2,
            page: 1,
            size: 10,
            records: [
              {
                executionId: 10001,
                executionNo: 'FP-20261111-00001',
                workflowId: 100,
                workflowVersionId: 200,
                status: 'RUNNING',
                totalNodes: 6,
                completedNodes: 2,
                failedNodes: 0,
                startedAt: '2026-11-11T09:30:00.000Z',
                finishedAt: null,
                createdAt: '2026-11-11T09:29:58.000Z'
              },
              {
                executionId: 10002,
                executionNo: 'FP-20261111-00002',
                workflowId: 100,
                workflowVersionId: 200,
                status: 'WAITING_HUMAN',
                totalNodes: 4,
                completedNodes: 3,
                failedNodes: 0,
                startedAt: '2026-11-11T08:10:00.000Z',
                finishedAt: null,
                createdAt: '2026-11-11T08:09:55.000Z'
              }
            ]
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10001') {
          send(200, {
            executionId: 10001,
            executionNo: 'FP-20261111-00001',
            workflowId: 100,
            workflowVersionId: 200,
            status: 'RUNNING',
            totalNodes: 6,
            completedNodes: 2,
            failedNodes: 0,
            input: { repositoryUrl: 'https://github.com/example/project' },
            output: null,
            errorCode: null,
            errorMessage: null,
            startedAt: '2026-11-11T09:30:00.000Z',
            finishedAt: null,
            createdAt: '2026-11-11T09:29:58.000Z'
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10003') {
          send(200, {
            executionId: 10003,
            executionNo: 'FP-20261111-00003',
            workflowId: 100,
            workflowVersionId: 200,
            status: 'RUNNING',
            totalNodes: 2,
            completedNodes: 0,
            failedNodes: 0,
            input: {},
            output: null,
            errorCode: null,
            errorMessage: null,
            startedAt: '2026-11-11T09:30:00.000Z',
            finishedAt: null,
            createdAt: '2026-11-11T09:29:58.000Z'
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10003/nodes') {
          send(200, [
            {
              nodeExecutionId: 20031,
              nodeKey: 'collect_repository',
              nodeType: 'HTTP',
              status: 'RUNNING',
              attempt: 1,
              maxAttempts: 3,
              startedAt: '2026-11-11T09:30:02.000Z',
              finishedAt: null,
              durationMs: null,
              errorCode: null,
              errorMessage: null
            }
          ])
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10003/events') {
          send(200, [])
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10001/nodes') {
          send(200, [
            {
              nodeExecutionId: 20001,
              nodeKey: 'collect_repository',
              nodeType: 'HTTP',
              status: 'SUCCEEDED',
              attempt: 1,
              maxAttempts: 3,
              startedAt: '2026-11-11T09:30:02.000Z',
              finishedAt: '2026-11-11T09:30:03.000Z',
              durationMs: 812,
              errorCode: null,
              errorMessage: null
            },
            {
              nodeExecutionId: 20002,
              nodeKey: 'analyze_repository',
              nodeType: 'AGENT',
              status: 'RETRY_WAIT',
              attempt: 2,
              maxAttempts: 3,
              startedAt: '2026-11-11T09:30:05.000Z',
              finishedAt: null,
              durationMs: null,
              errorCode: 'GITHUB_API_TIMEOUT',
              errorMessage: 'GitHub API 请求超时，正在等待重试'
            },
            {
              nodeExecutionId: 20003,
              nodeKey: 'review_report',
              nodeType: 'HUMAN',
              status: 'PENDING',
              attempt: 0,
              maxAttempts: 1,
              startedAt: null,
              finishedAt: null,
              durationMs: null,
              errorCode: null,
              errorMessage: null
            }
          ])
          return
        }
        if (method === 'GET' && path.startsWith('/api/v1/executions/10001/nodes/')) {
          send(200, [
            {
              attemptNo: 1,
              status: 'FAILED',
              workerId: 'worker-01',
              errorCode: 'GITHUB_API_TIMEOUT',
              errorMessage: 'GitHub API 请求超时',
              startedAt: '2026-11-11T09:30:05.000Z',
              finishedAt: '2026-11-11T09:30:35.000Z',
              durationMs: 30000
            },
            {
              attemptNo: 2,
              status: 'RETRY_WAIT',
              workerId: 'worker-02',
              errorCode: null,
              errorMessage: null,
              startedAt: '2026-11-11T09:30:37.000Z',
              finishedAt: null,
              durationMs: null
            }
          ])
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10001/events') {
          send(200, [
            {
              sequenceNo: 1,
              eventType: 'WORKFLOW_CREATED',
              nodeKey: null,
              eventData: { workflowId: 100, versionId: 200 },
              createdAt: '2026-11-11T09:29:58.000Z'
            },
            {
              sequenceNo: 2,
              eventType: 'NODE_STARTED',
              nodeKey: 'collect_repository',
              status: 'RUNNING',
              eventData: { attempt: 1 },
              createdAt: '2026-11-11T09:30:02.000Z'
            },
            {
              sequenceNo: 3,
              eventType: 'NODE_SUCCEEDED',
              nodeKey: 'collect_repository',
              status: 'SUCCEEDED',
              eventData: { durationMs: 812 },
              createdAt: '2026-11-11T09:30:03.000Z'
            }
          ])
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10001/events/stream') {
          // SSE：按 id: / event: / data: 帧格式推送，支持 ?lastEventId= 续传
          const requested = Number(
            (url.split('?')[1] ?? '').split('&').find((p) => p.startsWith('lastEventId='))?.split('=')[1] ??
              0
          )
          const start = Number.isFinite(requested) && requested > 0 ? requested : 0
          res.statusCode = 200
          res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
          res.setHeader('Cache-Control', 'no-cache')
          res.setHeader('Connection', 'keep-alive')
          res.setHeader('X-Accel-Buffering', 'no')

          const script: Array<[number, string, Record<string, unknown>]> = [
            [1, 'WORKFLOW_CREATED', { workflowId: 100, versionId: 200 }],
            [2, 'NODE_STARTED', { nodeKey: 'collect_repository', status: 'RUNNING' }],
            [3, 'NODE_SUCCEEDED', { nodeKey: 'collect_repository', status: 'SUCCEEDED' }],
            [4, 'NODE_STARTED', { nodeKey: 'analyze_repository', status: 'RUNNING' }],
            [5, 'AGENT_STARTED', { nodeKey: 'analyze_repository', status: 'RUNNING' }]
          ]

          const write = (seq: number, type: string, payload: Record<string, unknown>): void => {
            const body = JSON.stringify({
              sequenceNo: seq,
              eventType: type,
              nodeKey: payload.nodeKey ?? null,
              status: payload.status ?? null,
              eventData: payload,
              createdAt: new Date().toISOString()
            })
            res.write(`id: ${seq}\nevent: ${type}\ndata: ${body}\n\n`)
          }

          for (const [seq, type, payload] of script) {
            if (seq > start) write(seq, type, payload)
          }

          let seq = 6
          const timer = setInterval(() => {
            write(seq, 'AGENT_COMPLETED', { nodeKey: 'analyze_repository', status: 'SUCCEEDED' })
            seq += 1
          }, 4000)

          req.on('close', () => clearInterval(timer))
          return
        }
        if (method === 'GET' && path === '/api/v1/executions/10003/events/stream') {
          // 故意不稳定的事件流：每 3 秒断开一次，用于验证前端重连与续传
          const requested = Number(
            (url.split('?')[1] ?? '').split('&').find((p) => p.startsWith('lastEventId='))?.split('=')[1] ??
              0
          )
          const start = Number.isFinite(requested) && requested > 0 ? requested : 0
          res.statusCode = 200
          res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
          res.setHeader('Cache-Control', 'no-cache')
          res.setHeader('X-Accel-Buffering', 'no')

          let seq = Math.max(start, 0) + 1
          const push = (): void => {
            const body = JSON.stringify({
              sequenceNo: seq,
              eventType: 'NODE_SUCCEEDED',
              nodeKey: 'collect_repository',
              status: 'SUCCEEDED',
              eventData: { seq },
              createdAt: new Date().toISOString()
            })
            res.write(`id: ${seq}\nevent: NODE_SUCCEEDED\ndata: ${body}\n\n`)
            seq += 1
          }
          push()
          const timer = setInterval(push, 1000)
          const breaker = setTimeout(() => {
            clearInterval(timer)
            res.end()
          }, 3000)
          req.on('close', () => {
            clearInterval(timer)
            clearTimeout(breaker)
          })
          return
        }

        // ---- approvals ----
        if (method === 'GET' && path === '/api/v1/approvals') {
          send(200, {
            total: 2,
            page: 1,
            size: 10,
            records: [
              {
                approvalId: 40001,
                workflowExecutionId: 10001,
                nodeExecutionId: 20003,
                approvalType: 'REPORT_REVIEW',
                status: 'PENDING',
                title: '请审核技术尽调报告',
                description: '确认报告结论是否符合预期，决定是否放行最终产出。',
                requestData: { repositoryUrl: 'https://github.com/example/project' },
                decisionData: null,
                requestedAt: '2026-11-11T09:35:00.000Z',
                decidedAt: null,
                approverId: null,
                comment: null
              },
              {
                approvalId: 40002,
                workflowExecutionId: 10002,
                nodeExecutionId: 20012,
                approvalType: 'REPORT_REVIEW',
                status: 'PENDING',
                title: '请确认变更影响面结论',
                description: '复核受影响模块列表后放行。',
                requestData: null,
                decisionData: null,
                requestedAt: '2026-11-11T08:20:00.000Z',
                decidedAt: null,
                approverId: null,
                comment: null
              }
            ]
          })
          return
        }
        if (method === 'POST' && path.startsWith('/api/v1/approvals/')) {
          send(200, { approvalId: 40001, status: 'APPROVED' })
          return
        }

        // ---- reports ----
        if (method === 'GET' && path === '/api/v1/reports') {
          send(200, {
            total: 1,
            page: 1,
            size: 10,
            records: [
              {
                reportId: 50001,
                reportNo: 'RPT-20261111-00001',
                workflowExecutionId: 10001,
                title: 'example/project 技术尽调报告',
                repositoryUrl: 'https://github.com/example/project',
                reportType: 'GITHUB_DUE_DILIGENCE',
                status: 'PENDING_REVIEW',
                summary: '项目整体技术栈成熟，依赖风险可控，存在 2 项需要关注的问题。',
                createdAt: '2026-11-11T09:40:00.000Z'
              }
            ]
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/reports/50001') {
          send(200, {
            reportId: 50001,
            reportNo: 'RPT-20261111-00001',
            workflowExecutionId: 10001,
            title: 'example/project 技术尽调报告',
            repositoryUrl: 'https://github.com/example/project',
            reportType: 'GITHUB_DUE_DILIGENCE',
            status: 'PENDING_REVIEW',
            summary: '项目整体技术栈成熟，依赖风险可控。',
            content: {
              sections: [
                {
                  title: '项目概览',
                  content: '该项目是一个基于 Spring Boot 的后端服务，最近 12 个月保持较高提交频率。'
                },
                {
                  title: '风险项',
                  content: [
                    { title: '依赖漏洞', content: '存在 1 个高危依赖漏洞，建议尽快升级。' },
                    { title: '测试覆盖', content: '核心模块单测覆盖率约 61%。' }
                  ]
                },
                {
                  title: '关键指标',
                  content: {
                    stars: 1240,
                    forks: 132,
                    contributors: 48,
                    lastCommit: '2026-11-09'
                  }
                }
              ]
            },
            markdownContent: '# example/project 技术尽调报告\n\n## 项目概览\n\n该项目是…\n',
            contentHash: '9f2b7c1d4e5a',
            generatedAt: '2026-11-11T09:40:00.000Z',
            createdAt: '2026-11-11T09:39:58.000Z'
          })
          return
        }
        if (method === 'GET' && path === '/api/v1/reports/50001/download') {
          res.statusCode = 200
          res.setHeader('Content-Type', 'text/markdown; charset=utf-8')
          res.setHeader(
            'Content-Disposition',
            'attachment; filename="RPT-20261111-00001.md"'
          )
          res.end('# example/project 技术尽调报告\n\n## 项目概览\n\n该项目是…\n')
          return
        }

        next()
      })
    }
  }
}

export default defineConfig(({ mode }) => {
  const useMock = mode === 'mock' || process.env.FP_MOCK === '1'
  return {
    plugins: [vue(), ...(useMock ? [mockBackend()] : [])],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      host: true,
      proxy: {
        // 开发环境将 /api 代理到本地 Spring Boot 后端
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    preview: {
      port: 4173,
      host: true,
      proxy: {
        // 预览构建产物时同样需要代理，否则控制台无法访问后端
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      target: 'es2022',
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            element: ['element-plus', '@element-plus/icons-vue']
          }
        }
      }
    }
  }
})
