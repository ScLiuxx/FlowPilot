# FlowPilot
## 系统总体架构设计

**Document Version:** V1.0  
**Date:** 2026-11-11  
**Architecture Style:** Modular Monolith + Agent Microservice  
**Primary Language:** Java / Python

---

# 第一章 架构设计目标

## 1.1 核心目标

FlowPilot 采用 Java + Python 双服务架构。

Java 后端负责整个业务系统的任务管理、工作流编排和执行调度。

Python 服务负责 Agent Runtime，包括大模型调用、工具执行、动态决策和 Checkpoint 管理。

系统需要满足：

1. 工作流支持 DAG 依赖关系。
2. 支持多个节点并行执行。
3. 支持长时间运行的异步任务。
4. 支持任务失败重试。
5. 支持 Worker 故障恢复。
6. 支持 Agent 状态持久化。
7. 支持人工介入与任务恢复。
8. 支持多用户并发执行。
9. 支持完整的执行链路追踪。
10. 支持后续横向扩展。

## 1.2 架构设计原则

### 原则一：职责分离

业务系统不直接实现 Agent 内部逻辑。

Agent 服务不直接管理用户和工作流业务数据。

### 原则二：状态持久化优先

不将进程内存作为任务状态的唯一存储位置。

### 原则三：消息驱动

跨服务的耗时任务通过消息队列异步通信。

### 原则四：至少一次投递与消费端幂等

不承诺端到端 Exactly Once。

通过消息去重、业务幂等和状态机保证最终业务正确性。

### 原则五：故障可恢复

任务执行失败后，系统应能够判断任务是否可以重试，以及从哪个执行节点恢复。

---

# 第二章 总体系统架构

## 2.1 系统架构图

```text
                         Vue 3
                           |
                     HTTP / SSE
                           |
                      Nginx
                           |
                  Spring Boot Backend
                           |
        +------------------+------------------+
        |                  |                  |
   User Module       Workflow Module      Task Module
        |                  |                  |
        +------------------+------------------+
                           |
                    Workflow Engine
                           |
             +-------------+-------------+
             |             |             |
         DAG Parser    Scheduler     State Manager
             |             |             |
             +-------------+-------------+
                           |
                     MySQL 8
                           |
                     Redis 7
                           |
                       Outbox
                           |
                       RabbitMQ
                           |
              +------------+------------+
              |                         |
        Workflow Worker            Agent Worker
              |                         |
        Java Execution             FastAPI Service
              |                         |
        HTTP / Code Node              LangGraph
              |                         |
        Condition Node                LLM
              |                         |
              |                       Tools
              |                         |
              |                    PostgreSQL
              |                    Checkpoint
              |                         |
              +------------+------------+
                           |
                     Execution Events
                           |
                       RabbitMQ
                           |
                     Spring Boot
                           |
                     MySQL Update
                           |
                      SSE Push
                           |
                         Vue 3
```

## 2.2 架构分层

整个系统分为四个逻辑层。

### 第一层：接入层

主要组件：

- Vue 3
- Nginx
- Spring Boot REST API
- SSE

职责：

- 接收用户请求。
- 身份认证。
- 请求参数校验。
- 任务创建。
- 执行进度展示。

### 第二层：业务控制层

主要组件：

- User Module
- Workflow Module
- Task Module
- Workflow Engine

职责：

- 工作流管理。
- DAG 解析。
- 节点依赖管理。
- 任务状态管理。
- 执行计划生成。
- 任务调度。

### 第三层：执行层

分为两个执行环境。

Java Worker：

- HTTP 请求。
- 普通代码处理。
- 条件判断。
- 数据汇总。
- 普通业务节点。

Python Agent Worker：

- Agent 推理。
- LLM 调用。
- Tool Calling。
- 动态任务规划。
- 人工介入。
- Checkpoint。

### 第四层：基础设施层

包括：

- MySQL。
- Redis。
- RabbitMQ。
- PostgreSQL。
- Docker。
- OpenTelemetry。

---

# 第三章 服务边界设计

## 3.1 Java Backend

采用 Spring Boot 3，使用模块化单体架构。

第一版不将用户服务、工作流服务、任务服务拆成独立微服务。

内部模块划分：

```text
backend/
└── src/main/java/com/flowpilot/
    |
    ├── auth/
    |   ├── controller/
    |   ├── service/
    |   └── security/
    |
    ├── user/
    |   ├── controller/
    |   ├── service/
    |   ├── mapper/
    |   └── entity/
    |
    ├── workflow/
    |   ├── controller/
    |   ├── service/
    |   ├── definition/
    |   ├── parser/
    |   └── validator/
    |
    ├── execution/
    |   ├── engine/
    |   ├── scheduler/
    |   ├── state/
    |   ├── worker/
    |   └── recovery/
    |
    ├── task/
    |   ├── controller/
    |   ├── service/
    |   ├── entity/
    |   └── mapper/
    |
    ├── messaging/
    |   ├── producer/
    |   ├── consumer/
    |   ├── outbox/
    |   └── event/
    |
    ├── approval/
    |
    ├── report/
    |
    ├── monitoring/
    |
    └── common/
```

模块之间通过明确的服务接口交互。

数据库访问尽量遵循模块边界，避免任意模块直接修改其他模块的业务状态。

## 3.2 Python Agent Service

采用 FastAPI + LangGraph。

目录结构：

```text
agent-service/
|
├── app/
|   |
|   ├── api/
|   |   ├── health.py
|   |   └── internal.py
|   |
|   ├── runtime/
|   |   ├── executor.py
|   |   ├── registry.py
|   |   └── context.py
|   |
|   ├── graphs/
|   |   ├── research_graph.py
|   |   ├── analysis_graph.py
|   |   └── review_graph.py
|   |
|   ├── tools/
|   |   ├── github_tool.py
|   |   ├── dependency_tool.py
|   |   └── report_tool.py
|   |
|   ├── persistence/
|   |   ├── checkpoint.py
|   |   └── repository.py
|   |
|   ├── messaging/
|   |   ├── consumer.py
|   |   ├── producer.py
|   |   └── events.py
|   |
|   └── config/
|
├── tests/
├── Dockerfile
└── requirements.txt
```

Agent Service 通过 RabbitMQ 接收执行命令，执行结束后发布结果事件。

FastAPI 主要用于健康检查、内部管理接口和必要的调试接口。

## 3.3 服务通信方式

采用两种通信方式。

| 通信场景 | 通信方式 |
|---|---|
| 前端与 Java | HTTP |
| 实时执行进度 | SSE |
| Java 与 Agent | RabbitMQ |
| Agent 内部模型调用 | HTTP |
| Java 健康检查 Agent | HTTP |
| Agent 状态持久化 | PostgreSQL |

不建议通过同步 HTTP 调用完成整个 Agent 任务。

原因：

Agent 任务执行时间不可预测，可能持续几十秒甚至更长。

使用异步消息可以避免请求线程长期阻塞，并且更方便处理 Worker 故障。

---

# 第四章 核心数据存储设计

## 4.1 MySQL：业务数据权威存储

保存：

- 用户信息。
- 工作流定义。
- 工作流版本。
- 工作流执行记录。
- 节点执行记录。
- 审批记录。
- 诊断报告。
- Outbox 消息。

MySQL 是业务任务状态的唯一权威来源。

## 4.2 Redis：缓存与协调

主要用途：

1. 用户登录状态。
2. 工作流定义缓存。
3. 任务状态热点缓存。
4. 短期幂等键。
5. 分布式协调。
6. SSE 多实例订阅辅助。

注意：

Redis 不作为任务状态的唯一持久化存储。

即使 Redis 数据全部丢失，也应该能够通过 MySQL 恢复核心业务状态。

## 4.3 PostgreSQL：Agent Checkpoint

用于 LangGraph Checkpoint 持久化。

保存：

- Graph State。
- Thread ID。
- Checkpoint ID。
- 节点执行进度。
- 中断状态。
- 恢复所需的数据。

业务任务状态仍然以 MySQL 为准。

PostgreSQL 仅承担 Agent 内部执行状态的持久化职责。

第一版不额外引入 pgvector，避免不必要的基础设施复杂度。

## 4.4 为什么需要三个存储系统？

| 存储 | 解决的问题 |
|---|---|
| MySQL | 业务事务和任务状态 |
| Redis | 高频访问和短期协调 |
| PostgreSQL | LangGraph 原生 Checkpoint |

三个系统各有明确职责，不互相替代。

---

# 第五章 工作流引擎设计

## 5.1 工作流定义

第一版使用 JSON 描述 DAG。

示例：

```json
{
  "workflowId": "github-analysis",
  "version": 1,
  "nodes": [
    {
      "id": "project_info",
      "type": "HTTP",
      "config": {
        "action": "get_repository"
      }
    },
    {
      "id": "commit_analysis",
      "type": "HTTP",
      "config": {
        "action": "get_commits"
      }
    },
    {
      "id": "issue_analysis",
      "type": "HTTP",
      "config": {
        "action": "get_issues"
      }
    },
    {
      "id": "risk_analysis",
      "type": "AGENT",
      "dependsOn": [
        "project_info",
        "commit_analysis",
        "issue_analysis"
      ]
    },
    {
      "id": "report",
      "type": "CODE",
      "dependsOn": [
        "risk_analysis"
      ]
    }
  ]
}
```

正式实现时，需要统一节点输入输出协议，并对节点配置进行 Schema 校验。

## 5.2 DAG 校验

工作流发布前必须进行：

1. 节点 ID 唯一性校验。
2. 依赖节点存在性校验。
3. 自依赖检测。
4. 循环依赖检测。
5. 节点类型合法性校验。
6. 输入输出类型校验。
7. 孤立节点检查。

采用拓扑排序判断是否存在循环依赖。

建议使用 Kahn 算法：

时间复杂度：

O(V + E)

其中 V 为节点数量，E 为依赖边数量。

## 5.3 节点调度

当工作流开始执行时：

1. 初始化所有节点状态为 PENDING。
2. 找出所有没有依赖的节点。
3. 将这些节点转换为 READY。
4. 调度 READY 节点。
5. 节点执行成功后更新状态。
6. 检查后继节点依赖是否全部满足。
7. 满足条件的节点转换为 READY。
8. 所有节点执行结束后更新工作流状态。

调度器不直接执行耗时业务逻辑。

它负责决定哪些节点可以执行，并将任务交给对应 Worker。

## 5.4 并行执行

对于不存在依赖关系的节点，可以并行执行。

例如：

```text
          START
            |
      +-----+-----+
      |     |     |
      A     B     C
      |     |     |
      +-----+-----+
            |
            D
            |
           END
```

A、B、C 可以并行执行。

D 必须等待 A、B、C 全部成功。

并行度受到以下因素约束：

- 用户并发额度。
- Worker 数量。
- 节点资源限制。
- 全局执行上限。

---

# 第六章 任务状态机设计

这是 FlowPilot 最重要的模块之一。

## 6.1 工作流实例状态

定义以下状态：

| 状态 | 含义 |
|---|---|
| CREATED | 已创建 |
| QUEUED | 等待执行 |
| RUNNING | 执行中 |
| WAITING_HUMAN | 等待人工审批 |
| RETRY_WAIT | 等待重试 |
| SUCCEEDED | 执行成功 |
| FAILED | 执行失败 |
| CANCELED | 已取消 |

状态转换：

```text
CREATED
   |
   v
QUEUED
   |
   v
RUNNING
   |
   +-----------> WAITING_HUMAN
   |                   |
   |                   v
   |                RUNNING
   |
   +-----------> RETRY_WAIT
   |                   |
   |                   v
   |                RUNNING
   |
   +-----------> SUCCEEDED
   |
   +-----------> FAILED
   |
   +-----------> CANCELED
```

需要注意：

WAITING_HUMAN 是一种正常的暂停状态，不应该被认为是执行失败。

## 6.2 节点执行状态

| 状态 | 含义 |
|---|---|
| PENDING | 尚未满足执行条件 |
| READY | 可以执行 |
| RUNNING | 正在执行 |
| RETRY_WAIT | 等待重试 |
| WAITING_HUMAN | 等待人工输入 |
| SUCCEEDED | 执行成功 |
| FAILED | 执行失败 |
| SKIPPED | 被跳过 |
| CANCELED | 已取消 |

每次执行尝试都应单独记录 attempt。

不能只在节点表中保留最后一次执行结果，否则无法分析重试过程。

## 6.3 状态转换约束

状态变更必须经过统一的状态管理服务。

禁止业务代码直接随意修改状态字段。

例如：

```sql
UPDATE workflow_execution
SET status = 'RUNNING',
    version = version + 1
WHERE id = ?
  AND status = 'QUEUED'
  AND version = ?;
```

通过乐观锁避免多个 Worker 或调度线程同时修改状态。

更新行数为 0 时，说明状态已被其他操作修改，需要重新读取并判断。

---

# 第七章 RabbitMQ 消息架构

## 7.1 消息类型

定义以下主要消息：

| 消息 | 方向 | 用途 |
|---|---|---|
| WorkflowStarted | Backend → Worker | 启动工作流 |
| NodeExecute | Backend → Worker | 执行普通节点 |
| AgentExecute | Backend → Agent | 执行 Agent |
| AgentResume | Backend → Agent | 恢复 Agent |
| NodeCompleted | Worker → Backend | 节点成功 |
| NodeFailed | Worker → Backend | 节点失败 |
| AgentCompleted | Agent → Backend | Agent 成功 |
| AgentFailed | Agent → Backend | Agent 失败 |
| AgentInterrupted | Agent → Backend | Agent 中断 |

消息采用统一 Envelope：

```json
{
  "messageId": "msg-001",
  "messageType": "AgentExecute",
  "taskId": "task-001",
  "workflowExecutionId": "exec-001",
  "nodeExecutionId": "node-001",
  "attempt": 1,
  "traceId": "trace-001",
  "timestamp": "2026-11-11T10:00:00Z",
  "payload": {}
}
```

正式版本需要加入 schemaVersion、correlationId 和必要的幂等标识。

## 7.2 消息可靠性

消息生产者：

- 使用持久化消息。
- 使用 Publisher Confirm。
- 处理消息发送失败。
- 使用 Outbox Pattern。

消息消费者：

- 使用手动 ACK。
- 控制 Prefetch。
- 处理重复消息。
- 记录消费结果。
- 对不可恢复错误进入死信队列。

不使用无限立即重试。

## 7.3 Transactional Outbox

解决以下问题：

```text
MySQL 事务提交成功
        |
        v
服务突然宕机
        |
        v
RabbitMQ 消息没有发送
```

设计：

```text
BEGIN TRANSACTION

1. 更新任务状态
2. 插入 Outbox 记录

COMMIT

        |
        v
Outbox Publisher
        |
        v
RabbitMQ
```

Outbox Publisher 定期扫描未发送记录。

发送成功后更新消息状态。

如果发送成功但服务在更新状态之前宕机，消息可能重复发送。

因此消费者必须支持幂等处理。

## 7.4 消费端幂等

推荐使用：

messageId + consumerName

构建消费去重记录。

对于节点执行，还需要使用：

workflowExecutionId + nodeExecutionId + attempt

作为业务执行标识。

消息去重不能代替业务幂等。

真正重要的是保证节点重复执行不会造成重复业务副作用。

---

# 第八章 Java 与 LangGraph 状态协同

这是整个项目最值得深入研究的技术问题。

## 8.1 两套状态系统

Java 侧：

负责：

- Workflow Execution。
- Node Execution。
- Task Status。
- Attempt。
- Approval。

Python 侧：

负责：

- Agent Graph State。
- Thread。
- Checkpoint。
- Interrupt。
- Tool Execution State。

二者不能简单地共用一个状态字段。

## 8.2 标识关联

建立以下关联：

```text
workflowExecutionId
        |
        v
nodeExecutionId
        |
        v
agentInvocationId
        |
        v
LangGraph thread_id
        |
        v
checkpoint_id
```

一个 Agent 节点的一次业务执行对应一个稳定的 agentInvocationId。

使用该 ID 构建稳定的 LangGraph thread_id。

重试策略必须区分：

- 原执行恢复。
- 同一次执行的消息重复投递。
- 业务层主动重新执行。

不能让每次消息重投都创建新的 Thread。

## 8.3 执行流程

```text
Java Backend
     |
     | AgentExecute
     v
RabbitMQ
     |
     v
Python Agent Worker
     |
     v
查询执行记录
     |
     v
加载 LangGraph Checkpoint
     |
     v
执行 Graph
     |
     v
保存 Checkpoint
     |
     v
生成 AgentCompleted
     |
     v
RabbitMQ
     |
     v
Java Backend
     |
     v
校验当前节点状态
     |
     v
更新节点状态
     |
     v
继续调度后继节点
```

## 8.4 关键一致性问题

假设：

1. Agent 已经执行成功。
2. Checkpoint 已经保存。
3. AgentCompleted 消息已经发送。
4. Java 服务还没有更新任务状态。
5. Agent 服务突然宕机。

此时 Java 侧可能仍然认为节点处于 RUNNING。

解决方案：

- Agent 执行结果必须持久化。
- 结果事件通过 Outbox 发送。
- Agent 消费命令时使用稳定 invocationId。
- Java 收到结果事件后进行幂等状态转换。
- Java 恢复扫描时能够重新查询 Agent 执行结果。

这里不能假设 MySQL 和 PostgreSQL 之间存在原子事务。

采用最终一致性和可重放事件解决跨服务协调问题。

---

# 第九章 Worker 故障恢复设计

## 9.1 问题

假设一个 Worker 正在执行任务：

```text
任务状态：RUNNING

Worker A
   |
   v
执行节点
   |
   v
进程突然崩溃
```

如果系统没有额外机制，任务可能永久停留在 RUNNING。

## 9.2 Lease + Heartbeat

为正在执行的节点引入执行租约。

字段：

- workerId
- leaseToken
- leaseExpireAt
- heartbeatAt

Worker 定期发送心跳。

如果超过租约期限没有收到心跳，系统可以判定当前执行实例可能已经失效。

## 9.3 恢复流程

```text
Recovery Scanner
       |
       v
查询过期 RUNNING 节点
       |
       v
检查节点执行记录
       |
       v
判断是否可以恢复
       |
       +------+
       |      |
      可以    不可以
       |      |
       v      v
重新调度   标记 FAILED
       |
       v
更新执行尝试
       |
       v
发布执行消息
```

注意：

过期租约不意味着原 Worker 一定已经停止。

可能只是网络分区或者心跳延迟。

因此需要使用递增的 fencing token，防止旧 Worker 在失去租约后继续提交过期结果。

节点完成时必须验证：

- 当前执行 attempt。
- 当前 leaseToken。
- 当前节点状态。

旧执行实例不能覆盖新执行实例的结果。

## 9.4 Agent 恢复

Agent 恢复需要先检查 Checkpoint。

如果存在可恢复的执行状态，则尝试从原 Thread 恢复。

如果没有 Checkpoint，则根据业务策略重新开始。

对于可能产生外部副作用的 Tool，必须单独实现幂等机制。

---

# 第十章 人工介入机制

## 10.1 设计目标

支持 Agent 在执行过程中暂停，等待用户提供输入。

例如：

Agent 生成了一份风险报告，系统要求用户确认是否继续生成最终报告。

## 10.2 执行流程

```text
Agent Execution
       |
       v
风险分析
       |
       v
触发 Interrupt
       |
       v
保存 Checkpoint
       |
       v
发布 AgentInterrupted
       |
       v
Java 更新任务状态
       |
       v
WAITING_HUMAN
       |
       v
用户提交审批
       |
       v
Java 发布 AgentResume
       |
       v
LangGraph 恢复
       |
       v
继续执行
```

审批接口必须具备：

- 用户身份验证。
- 任务归属校验。
- 审批状态校验。
- 幂等处理。
- 超时处理。

## 10.3 重要注意事项

LangGraph 的 Interrupt 恢复时，触发 Interrupt 的节点会从头重新执行。

因此：

- 中断前的副作用必须幂等。
- 重要外部操作应尽可能放在 Interrupt 之后。
- 不应将不可重复的操作直接放在中断前。

---

# 第十一章 实时执行进度

## 11.1 SSE 架构

前端通过 SSE 订阅任务执行事件。

```text
Worker
  |
  v
RabbitMQ
  |
  v
Spring Boot
  |
  v
Redis Pub/Sub（多实例时）
  |
  v
SSE Connection
  |
  v
Vue 3
```

SSE 只负责展示执行进度，不作为任务状态的权威存储。

如果客户端断开连接，可以通过 taskId 重新查询执行状态。

## 11.2 事件示例

```json
{
  "eventId": "event-001",
  "taskId": "task-001",
  "nodeId": "risk_analysis",
  "status": "RUNNING",
  "message": "正在分析项目风险",
  "timestamp": "2026-11-11T10:30:00Z"
}
```

应支持 Last-Event-ID 或基于事件序号的断线补偿。

---

# 第十二章 可观测性设计

采用 OpenTelemetry 作为统一的观测标准。

每次业务任务具有唯一 Trace ID。

建议建立以下 Span：

```text
workflow.execution
    |
    +--- workflow.scheduler
    |
    +--- node.project_info
    |
    +--- node.commit_analysis
    |
    +--- node.issue_analysis
    |
    +--- node.risk_analysis
            |
            +--- agent.graph
            |
            +--- llm.call
            |
            +--- tool.github_search
            |
            +--- tool.dependency_scan
    |
    +--- node.report
```

需要统计：

- 工作流执行耗时。
- 节点执行耗时。
- Agent 执行耗时。
- LLM 调用次数。
- Token 消耗。
- 工具调用成功率。
- 节点重试次数。
- 工作流失败率。

同时注意避免在日志和 Trace 中泄漏 API Key、用户隐私及敏感提示词。

---

# 第十三章 部署架构

第一版采用 Docker Compose。

```text
                  Docker Host
                      |
       +--------------+--------------+
       |              |              |
     Backend        Agent         Frontend
       |              |              |
       +--------------+--------------+
                      |
       +--------------+--------------+
       |              |              |
      MySQL          Redis        RabbitMQ
       |
   PostgreSQL
       |
   Monitoring
```

建议容器：

1. frontend
2. backend-api
3. backend-worker
4. agent-worker
5. mysql
6. redis
7. rabbitmq
8. postgres
9. prometheus
10. grafana

其中 backend-api 和 backend-worker 使用同一套代码，通过不同启动配置运行。

Agent Worker 也可以根据并发需求增加实例。

第一版不引入 Kubernetes。

---

# 第十四章 技术选型最终确定

| 领域 | 技术 | 选择理由 |
|---|---|---|
| 开发语言 | Java 21 | 主攻后端开发 |
| 后端框架 | Spring Boot 3 | 成熟企业级生态 |
| ORM | MyBatis-Plus | 降低基础 CRUD 成本 |
| 数据库 | MySQL 8 | 业务数据持久化 |
| 缓存 | Redis 7 | 缓存和协调 |
| 消息队列 | RabbitMQ | 任务分发和消息确认 |
| Agent | LangGraph | 有状态 Agent |
| Python Web | FastAPI | 内部服务接口 |
| Checkpoint | PostgreSQL | Agent 状态持久化 |
| 前端 | Vue 3 | 管理控制台 |
| 实时通信 | SSE | 单向执行进度推送 |
| 部署 | Docker Compose | 简化开发部署 |
| 监控 | OpenTelemetry | 分布式追踪 |

---

# 第十五章 架构风险与后续优化

## 15.1 双数据库状态一致性

风险：

MySQL 和 PostgreSQL 之间没有天然原子事务。

处理：

通过稳定业务 ID、Outbox、幂等事件和恢复扫描实现最终一致性。

## 15.2 消息重复消费

风险：

RabbitMQ 消息可能重新投递。

处理：

消费去重、业务幂等、状态条件更新。

## 15.3 Worker 误判宕机

风险：

网络问题可能导致租约过期，但原 Worker 仍然运行。

处理：

Fencing Token 和执行实例校验。

## 15.4 Agent 执行成本

风险：

Agent 可能循环调用模型或工具，造成不必要的成本。

处理：

- 最大执行步数。
- 最大 Tool 调用次数。
- Token 预算。
- 超时限制。
- 用户级并发限制。

## 15.5 架构过度复杂

风险：

基础设施太多导致开发成本上升。

处理：

先实现单机 Docker Compose 版本。

所有模块先在有限规模下验证正确性，再考虑横向扩展。

---

# 第十六章 总结

FlowPilot 采用模块化单体 + 独立 Agent Runtime 的架构。

系统的核心不是简单地将工作流和 Agent 拼接起来，而是解决两者之间的协同问题。

重点建设：

1. DAG 工作流执行引擎。
2. 可靠异步任务调度系统。
3. Java 与 LangGraph 的状态协同。
4. Worker 故障恢复机制。
5. Agent 人工介入与 Checkpoint。
6. 全链路可观测系统。

项目最终应具备完整的业务闭环和可验证的工程能力。

**架构设计的最终原则：让确定性系统管理边界，让 Agent 在边界内自主决策。**