# FlowPilot 核心接口与消息协议设计 V1.0

**项目名称：** FlowPilot  
**文档版本：** V1.0  
**核心模块：** Java Backend、Python Agent Service、RabbitMQ  
**协议类型：** REST API、JSON、AMQP、SSE  
**设计目标：** 统一服务间通信规范，保证异步任务执行、状态同步、消息幂等和故障恢复。

---

# 1. 设计目标与通信架构

## 1.1 为什么需要统一通信协议？

FlowPilot 采用 Java + Python 的混合架构。

Java 负责工作流执行引擎，Python 负责 Agent Runtime。

两者虽然属于同一个系统，但运行环境、技术栈和业务职责不同。

如果没有统一协议，开发过程中容易出现：

- Java 发送的字段与 Python 接收的字段不一致。
- 不同消息使用不同的 ID 命名规则。
- Agent 执行成功后，Java 无法准确关联对应节点。
- 消息重复投递导致重复执行。
- 服务重启后无法恢复原来的 Agent 调用。
- 异常信息在不同服务之间丢失。
- Trace ID 无法跨服务传播。

因此，需要设计统一的通信协议。

本设计主要遵循以下原则：

1. **协议与实现解耦：** Java 和 Python 只依赖消息协议，不依赖对方的内部代码结构。
2. **业务标识统一：** 所有消息使用统一的业务执行标识。
3. **异步优先：** 长时间运行的 Agent 任务采用异步消息通信。
4. **幂等处理：** 所有可能重复投递的消息都必须具备幂等处理能力。
5. **版本兼容：** 消息协议需要包含版本号。
6. **状态权威明确：** Java 管理业务执行状态，Python 管理 Agent 内部执行状态。

## 1.2 系统通信架构

```text
                       Vue 3
                         |
                    HTTP / SSE
                         |
                         v
                  Java Backend
                         |
          +--------------+--------------+
          |              |              |
          v              v              v
     Workflow       Scheduler       Report
      Service         Engine         Service
                         |
                         v
                    RabbitMQ
                         |
              +----------+----------+
              |                     |
              v                     v
       Agent Service           Java Worker
              |                     |
              v                     v
          LangGraph            HTTP / CODE
              |
              v
          PostgreSQL
          Checkpoint
```

通信方式：

| 通信双方 | 通信协议 | 主要用途 |
|---|---|---|
| Vue → Java | HTTP | 用户请求 |
| Java → Vue | SSE | 工作流执行进度 |
| Java → RabbitMQ | AMQP | 发布异步任务 |
| RabbitMQ → Java | AMQP | Worker 消费任务 |
| RabbitMQ → Python | AMQP | Agent 执行任务 |
| Python → RabbitMQ | AMQP | Agent 执行结果 |
| Java → Python | AMQP | Agent 恢复、取消等命令 |
| Python → PostgreSQL | PostgreSQL 协议 | Checkpoint 持久化 |

第一阶段不需要为所有服务设计复杂的 REST RPC。

Java 和 Python 之间优先使用 RabbitMQ 异步通信，避免 Java 线程长时间等待 Agent 执行。

---

# 2. 统一业务标识设计

## 2.1 为什么需要统一 ID？

考虑一次完整执行：

```text
Workflow Execution
        |
        v
Node Execution
        |
        v
Node Attempt
        |
        v
Agent Invocation
        |
        v
LangGraph Thread
        |
        v
Checkpoint
```

每一层都有自己的生命周期。

如果只使用一个 workflowExecutionId，无法准确定位某一次节点执行。

如果只使用 thread_id，也无法直接关联 Java 业务任务。

因此，定义以下标识：

| 标识 | 类型 | 作用 |
|---|---|---|
| workflowExecutionId | Long | 工作流执行实例 ID |
| nodeExecutionId | Long | 节点执行实例 ID |
| attemptNo | Integer | 节点执行尝试次数 |
| invocationId | Long | Agent 调用 ID |
| messageId | String | 消息唯一标识 |
| correlationId | String | 一组关联请求的标识 |
| causationId | String | 当前消息的直接上游消息 ID |
| traceId | String | 分布式追踪 ID |
| threadId | String | LangGraph 线程 ID |
| checkpointId | String | LangGraph Checkpoint ID |

需要区分三个容易混淆的概念：

**messageId：**

标识一条消息，用于消息去重。

**correlationId：**

标识一组相关业务请求。例如一次 Agent 调用产生的多个事件，可以共享同一个 correlationId。

**traceId：**

标识一次分布式追踪，用于关联 Java、RabbitMQ 和 Python 服务中的 Span。

三者不能混用。

## 2.2 ID 生成规则

建议：

- 数据库主键：雪花 ID，使用 BIGINT。
- messageId：UUID。
- correlationId：UUID。
- traceId：采用 OpenTelemetry Trace ID，使用 32 位十六进制字符串。
- threadId：使用稳定的业务生成规则。
- checkpointId：由 LangGraph Checkpoint 机制管理。

threadId 可以使用：

```text
flowpilot-{invocationId}
```

例如：

```text
flowpilot-100000012345
```

这样同一个 Agent Invocation 恢复执行时，能够继续使用相同的 threadId。

不要在每次消息重投时重新生成 threadId，否则会导致 Agent 无法恢复原来的 Checkpoint。

---

# 3. 统一消息 Envelope

## 3.1 消息结构

所有 RabbitMQ 消息采用统一 Envelope 格式。

```json
{
  "schemaVersion": "1.0",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "AgentExecute",
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "causationId": null,
  "workflowExecutionId": 10001,
  "nodeExecutionId": 20001,
  "attemptNo": 1,
  "invocationId": 30001,
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-11T10:00:00.000Z",
  "producer": "flowpilot-backend",
  "payload": {}
}
```

字段定义：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| schemaVersion | String | 是 | 消息协议版本 |
| messageId | String | 是 | 消息唯一 ID |
| messageType | String | 是 | 消息类型 |
| correlationId | String | 是 | 关联 ID |
| causationId | String | 否 | 上游消息 ID |
| workflowExecutionId | Long | 是 | 工作流执行 ID |
| nodeExecutionId | Long | 否 | 节点执行 ID |
| attemptNo | Integer | 否 | 执行尝试次数 |
| invocationId | Long | 否 | Agent 调用 ID |
| traceId | String | 是 | Trace ID |
| timestamp | String | 是 | 消息创建时间 |
| producer | String | 是 | 消息生产者 |
| payload | Object | 是 | 业务数据 |

注意：

Envelope 只保存跨服务通信必需的信息。

不要把所有业务对象直接序列化到消息中。

对于较大的数据，使用对象存储地址、数据集 ID 或业务资源 ID 传递。

## 3.2 Java 消息对象

```java
@Data
public class MessageEnvelope<T> {

    private String schemaVersion;

    private String messageId;

    private String messageType;

    private String correlationId;

    private String causationId;

    private Long workflowExecutionId;

    private Long nodeExecutionId;

    private Integer attemptNo;

    private Long invocationId;

    private String traceId;

    private Instant timestamp;

    private String producer;

    private T payload;
}
```

使用泛型 payload，避免不同消息类型各自重新定义 Envelope。

## 3.3 Python 消息对象

Python 可以使用 Pydantic 定义统一结构。

```python
from datetime import datetime
from typing import Any, Optional
from pydantic import BaseModel


class MessageEnvelope(BaseModel):

    schemaVersion: str

    messageId: str

    messageType: str

    correlationId: str

    causationId: Optional[str] = None

    workflowExecutionId: int

    nodeExecutionId: Optional[int] = None

    attemptNo: Optional[int] = None

    invocationId: Optional[int] = None

    traceId: str

    timestamp: datetime

    producer: str

    payload: dict[str, Any]
```

Java 和 Python 必须遵循相同的字段定义。

实际开发中，建议将消息协议整理成独立的 JSON Schema 文件，由两端共同维护。

---

# 4. RabbitMQ Exchange 与 Queue 设计

## 4.1 Exchange 规划

建议第一阶段采用 Topic Exchange。

Exchange：

```text
flowpilot.events
```

消息通过 Routing Key 分发。

例如：

```text
workflow.started
node.execute
node.completed
node.failed
agent.execute
agent.resume
agent.cancel
agent.completed
agent.failed
agent.interrupted
```

Topic Exchange 可以通过通配符订阅相关消息。

例如：

```text
agent.*
```

可以匹配所有以 agent 开头的路由键。

## 4.2 Queue 规划

建议第一阶段定义以下队列：

| Queue | 消费者 | 作用 |
|---|---|---|
| workflow.scheduler.queue | Java Scheduler | 工作流启动和调度事件 |
| node.execute.queue | Java Worker | 普通节点执行 |
| agent.execute.queue | Python Agent Service | Agent 执行 |
| agent.control.queue | Python Agent Service | Agent 恢复与取消 |
| backend.agent-events.queue | Java Backend | Agent 结果事件 |
| flowpilot.dead-letter.queue | 运维处理 | 死信消息 |

绑定关系：

```text
flowpilot.events
       |
       +---- workflow.started
       |          |
       |          v
       |    workflow.scheduler.queue
       |
       +---- node.execute
       |          |
       |          v
       |      node.execute.queue
       |
       +---- agent.execute
       |          |
       |          v
       |     agent.execute.queue
       |
       +---- agent.resume
       |          |
       |          v
       |     agent.control.queue
       |
       +---- agent.cancel
       |          |
       |          v
       |     agent.control.queue
       |
       +---- agent.completed
       |          |
       |          v
       |  backend.agent-events.queue
       |
       +---- agent.failed
       |          |
       |          v
       |  backend.agent-events.queue
       |
       +---- agent.interrupted
                  |
                  v
          backend.agent-events.queue
```

同一个队列可以有多个消费者实例，实现横向扩展。

注意：

RabbitMQ 中同一个队列的多个消费者通常是竞争消费关系，而不是广播关系。

如果多个不同服务都需要收到同一事件，应该为它们建立独立队列并分别绑定 Exchange。

## 4.3 消息持久化配置

建议：

- Exchange 使用 durable=true。
- Queue 使用 durable=true。
- 消息使用 persistent delivery mode。
- 开启 Publisher Confirm。
- 消费者采用手动 ACK。
- 配置死信交换机和死信队列。
- 设置合理的 Prefetch。
- 为消息处理配置超时和异常策略。

消息可靠性需要结合数据库 Outbox 使用。

Publisher Confirm 只能说明 RabbitMQ 对发布消息进行了确认，不等于消费者已经完成业务处理。

Consumer ACK 也不能代替业务事务。

---

# 5. 核心消息类型设计

第一阶段只需要实现少量关键消息。

## 5.1 WorkflowStarted

生产者：Java Backend。

消费者：Workflow Scheduler。

作用：通知调度器工作流执行实例已经创建。

Payload：

```json
{
  "workflowId": 100,
  "workflowVersionId": 101,
  "executionId": 10001,
  "input": {
    "repositoryUrl": "https://github.com/example/project"
  }
}
```

消费后：

1. 查询工作流执行实例。
2. 检查执行状态。
3. 初始化调度上下文。
4. 查找初始节点。
5. 将满足条件的节点调度为 READY。
6. 写入对应的 NodeExecute Outbox。

## 5.2 NodeExecute

生产者：Java Scheduler。

消费者：Java Worker。

作用：通知 Worker 执行一个节点。

Payload：

```json
{
  "nodeKey": "collect_repository",
  "nodeType": "HTTP",
  "config": {
    "operation": "get_repository"
  },
  "input": {
    "repositoryUrl": "https://github.com/example/project"
  },
  "timeoutSeconds": 30
}
```

Worker 接收到消息后：

1. 校验消息结构。
2. 查询节点执行记录。
3. 检查 attemptNo。
4. 检查是否已经完成。
5. 领取执行权。
6. 执行对应 NodeExecutor。
7. 提交执行结果。

## 5.3 NodeCompleted

生产者：Java Worker。

消费者：Java Scheduler。

作用：通知调度器节点执行成功。

Payload：

```json
{
  "nodeKey": "collect_repository",
  "result": {
    "repositoryId": 123456,
    "fullName": "example/project",
    "stars": 12000,
    "forks": 1500
  }
}
```

调度器收到后：

1. 校验节点状态。
2. 检查业务执行结果。
3. 查询后继节点。
4. 检查后继节点依赖。
5. 调度满足条件的节点。

需要注意：

NodeCompleted 不是让调度器盲目信任消息中的 result，而是根据数据库中的权威执行状态完成后续调度。

## 5.4 NodeFailed

生产者：Java Worker。

消费者：Java Scheduler。

Payload：

```json
{
  "errorCode": "GITHUB_API_TIMEOUT",
  "errorCategory": "TRANSIENT",
  "errorMessage": "GitHub API request timeout",
  "retryable": true
}
```

调度器根据节点配置和异常分类决定：

- 进入 RETRY_WAIT。
- 创建新的 Attempt。
- 或者将节点设置为 FAILED。

## 5.5 AgentExecute

生产者：Java Backend。

消费者：Python Agent Service。

这是 Java 和 Python 之间最重要的消息。

Payload：

```json
{
  "agentType": "REPOSITORY_ANALYST",
  "threadId": "flowpilot-30001",
  "invocationId": 30001,
  "input": {
    "repository": {
      "fullName": "example/project",
      "stars": 12000
    },
    "commits": {
      "total": 500,
      "recentActivity": 30
    },
    "issues": {
      "open": 120,
      "closed": 800
    }
  },
  "config": {
    "model": "configured-model",
    "maxIterations": 10,
    "maxToolCalls": 20,
    "timeoutSeconds": 300
  }
}
```

Python Agent Service 收到后：

1. 验证 invocationId。
2. 检查是否存在相同调用。
3. 检查当前调用状态。
4. 创建或恢复 LangGraph 执行。
5. 使用 threadId 作为 Checkpoint 关联标识。
6. 执行 Agent。
7. 产生 AgentCompleted、AgentFailed 或 AgentInterrupted 事件。

注意：

AgentExecute 的消息重复投递时，不能直接重新启动一个 Agent。

必须先检查 invocationId 对应的执行记录。

## 5.6 AgentCompleted

生产者：Python Agent Service。

消费者：Java Backend。

Payload：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "checkpointId": "checkpoint-001",
  "result": {
    "architectureAnalysis": "项目采用分层架构...",
    "maintenanceAnalysis": "项目近期仍有活跃维护...",
    "riskAnalysis": "需要进一步关注依赖风险...",
    "recommendation": "建议继续技术评估"
  },
  "usage": {
    "inputTokens": 5000,
    "outputTokens": 2000
  }
}
```

Java 收到后：

1. 验证 invocationId。
2. 检查 nodeExecutionId 和 attemptNo。
3. 检查 Agent 调用状态。
4. 更新 agent_invocation。
5. 更新 node_execution。
6. 写入执行事件。
7. 调度后续节点。

结果消息应携带必要的业务关联标识。

不能仅凭 threadId 更新业务状态。

## 5.7 AgentFailed

生产者：Python Agent Service。

消费者：Java Backend。

Payload：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "errorCode": "LLM_REQUEST_TIMEOUT",
  "errorCategory": "TRANSIENT",
  "errorMessage": "Model request timed out",
  "retryable": true
}
```

Java Backend 根据业务重试策略决定是否重试。

Python 负责报告 Agent 执行结果，不应该直接决定整个工作流是否失败。

## 5.8 AgentInterrupted

生产者：Python Agent Service。

消费者：Java Backend。

Payload：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "checkpointId": "checkpoint-002",
  "interruptType": "HUMAN_APPROVAL",
  "interruptData": {
    "title": "请审核技术尽调报告",
    "description": "Agent 已完成初步分析，需要人工确认。",
    "requestedAction": "APPROVE_REPORT"
  }
}
```

Java 收到后：

1. 验证 Agent 调用。
2. 保存最新 Checkpoint 关联信息。
3. 更新 Agent 调用状态。
4. 将节点更新为 WAITING_HUMAN。
5. 创建 approval_record。
6. 更新工作流状态。
7. 推送 SSE 事件。

## 5.9 AgentResume

生产者：Java Backend。

消费者：Python Agent Service。

Payload：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "resumeRequestId": "resume-001",
  "approvalId": 40001,
  "resumeValue": {
    "approved": true,
    "comment": "报告内容符合要求"
  }
}
```

Python 收到后：

1. 验证 invocationId。
2. 验证当前 Agent 是否处于可恢复状态。
3. 检查 resumeRequestId 是否已处理。
4. 使用原 threadId 恢复 LangGraph。
5. 通过 Command(resume=...) 提交审批结果。
6. 继续执行并发送最终事件。

resumeRequestId 用于防止重复恢复。

## 5.10 AgentCancel

生产者：Java Backend。

消费者：Python Agent Service。

Payload：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "cancelReason": "USER_REQUEST"
}
```

Python 收到后：

1. 检查当前 Agent 状态。
2. 请求取消执行。
3. 尽可能停止后续模型和工具调用。
4. 保存必要的执行状态。
5. 返回取消结果。

取消属于协作式控制，不保证所有已经发出的外部请求都能撤销。

---

# 6. 消息消费与幂等设计

## 6.1 为什么消息必须幂等？

RabbitMQ 使用至少一次投递时，消费者可能收到重复消息。

例如：

```text
1. Worker 收到 NodeExecute
2. 执行成功
3. 数据库事务提交
4. Worker 在 ACK 前崩溃
5. RabbitMQ 重新投递消息
```

如果没有幂等处理，节点可能被执行两次。

因此必须将幂等作为协议和业务设计的一部分。

## 6.2 消息幂等规则

不同消息使用不同的业务幂等键。

| 消息类型 | 幂等依据 |
|---|---|
| WorkflowStarted | workflowExecutionId |
| NodeExecute | nodeExecutionId + attemptNo |
| NodeCompleted | nodeExecutionId + attemptNo |
| NodeFailed | nodeExecutionId + attemptNo |
| AgentExecute | invocationId |
| AgentCompleted | invocationId + 终态检查 |
| AgentFailed | invocationId + 终态检查 |
| AgentInterrupted | invocationId + checkpoint/中断事件标识 |
| AgentResume | invocationId + resumeRequestId |
| AgentCancel | invocationId + 取消请求标识 |

messageId 用于消息级去重。

业务幂等键用于防止不同消息 ID 对应同一次业务操作。

## 6.3 消费者处理模板

建议采用以下处理流程：

```text
收到消息
   |
   v
解析 Envelope
   |
   v
校验协议版本
   |
   v
校验业务标识
   |
   v
开启数据库事务
   |
   v
检查幂等记录
   |
   +---- 已处理
   |       |
   |       v
   |      ACK
   |
   +---- 未处理
           |
           v
      执行业务状态转换
           |
           v
      写入事件记录
           |
           v
      插入 Outbox
           |
           v
        提交事务
           |
           v
          ACK
```

注意：

ACK 必须在业务事务成功提交后进行。

如果业务处理失败并且需要重试，应根据异常类型选择 NACK/requeue、延迟重试或死信处理。

不要无条件 requeue，否则错误消息可能形成无限重试循环。

---

# 7. 消息可靠性与失败处理

## 7.1 Publisher Confirm

生产者发送消息后，需要等待 RabbitMQ Publisher Confirm。

确认成功后，才能将 Outbox 记录标记为 PUBLISHED。

如果发送超时或确认结果未知，不应该直接认定消息发送失败并删除记录。

应保留 Outbox 记录，允许后续重新发送。

重复投递由消费者幂等处理。

## 7.2 Consumer ACK

消费者在业务事务提交成功后 ACK。

常见处理策略：

| 情况 | 处理方式 |
|---|---|
| 业务执行成功 | ACK |
| 重复消息 | ACK |
| 临时数据库故障 | 有限重试 |
| 消息结构非法 | 拒绝并进入死信 |
| 不支持的协议版本 | 隔离并告警 |
| 不可恢复业务错误 | 记录失败并 ACK 或进入死信 |

对于可恢复异常，建议通过持久化重试机制控制重试次数，而不是无限制重新入队。

## 7.3 Dead Letter Queue

死信队列用于接收无法正常处理的消息。

例如：

```text
agent.execute.queue
       |
       v
消费失败
       |
       v
重试达到上限
       |
       v
Dead Letter Exchange
       |
       v
flowpilot.dead-letter.queue
```

死信消息需要保留：

- 原 messageId。
- 原 routingKey。
- 原 payload。
- 失败原因。
- 投递次数。
- 最后一次错误时间。

运维人员可以根据死信记录进行分析和人工补偿。

不能把死信队列当作正常业务重试队列。

---

# 8. REST API 设计

REST API 面向 Vue 前端，主要负责用户交互和业务查询。

统一前缀：

```text
/api/v1
```

## 8.1 统一响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "req-001",
  "timestamp": "2026-11-11T10:00:00.000Z"
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| code | 业务响应码 |
| message | 响应说明 |
| data | 业务数据 |
| requestId | 请求关联标识 |
| timestamp | 响应时间 |

建议：

- HTTP 状态码表达 HTTP 层结果。
- code 表达业务层结果。
- 不要所有错误都返回 HTTP 200。

## 8.2 创建工作流执行

```http
POST /api/v1/workflows/{workflowId}/executions
```

请求：

```json
{
  "workflowVersionId": 101,
  "input": {
    "repositoryUrl": "https://github.com/example/project"
  },
  "idempotencyKey": "github-review-20261111-001"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "executionId": 10001,
    "executionNo": "FP-20261111-00001",
    "status": "QUEUED"
  }
}
```

接口只负责创建执行实例，不等待整个工作流执行完成。

## 8.3 查询工作流执行详情

```http
GET /api/v1/executions/{executionId}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "executionId": 10001,
    "status": "RUNNING",
    "workflowVersionId": 101,
    "totalNodes": 6,
    "completedNodes": 2,
    "failedNodes": 0,
    "startedAt": "2026-11-11T10:00:00.000Z"
  }
}
```

## 8.4 查询节点执行详情

```http
GET /api/v1/executions/{executionId}/nodes
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "nodeExecutionId": 20001,
      "nodeKey": "collect_repository",
      "nodeType": "HTTP",
      "status": "SUCCEEDED",
      "attempt": 1
    },
    {
      "nodeExecutionId": 20002,
      "nodeKey": "analyze_repository",
      "nodeType": "AGENT",
      "status": "RUNNING",
      "attempt": 1
    }
  ]
}
```

## 8.5 查询执行事件

```http
GET /api/v1/executions/{executionId}/events
```

支持：

```text
?afterSequence=10
&limit=100
```

通过 sequence_no 实现增量查询。

## 8.6 取消工作流

```http
POST /api/v1/executions/{executionId}/cancel
```

请求：

```json
{
  "reason": "USER_REQUEST"
}
```

响应：

```json
{
  "code": 0,
  "message": "Cancellation requested",
  "data": {
    "executionId": 10001,
    "status": "CANCEL_REQUESTED"
  }
}
```

## 8.7 查询待审批任务

```http
GET /api/v1/approvals
```

支持分页和状态筛选。

例如：

```text
GET /api/v1/approvals?status=PENDING&page=1&size=20
```

## 8.8 提交审批

```http
POST /api/v1/approvals/{approvalId}/decision
```

请求：

```json
{
  "decision": "APPROVED",
  "comment": "技术分析结果符合要求"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "approvalId": 40001,
    "status": "APPROVED"
  }
}
```

审批接口必须进行权限检查和状态条件更新。

重复提交不能改变已经完成的审批结果。

## 8.9 查询报告

```http
GET /api/v1/reports/{reportId}
```

下载报告：

```http
GET /api/v1/reports/{reportId}/download
```

报告下载需要校验用户访问权限。

---

# 9. SSE 实时进度协议

## 9.1 为什么采用 SSE？

工作流执行具有明显的服务端推送需求。

例如：

```text
工作流已启动
仓库信息采集完成
Commit 分析完成
Issue 分析完成
Agent 开始分析
等待人工审批
报告生成完成
```

前端需要实时展示这些事件。

第一阶段使用 SSE，而不是 WebSocket。

原因：

- 主要需求是服务端向客户端推送。
- SSE 基于 HTTP。
- 浏览器原生支持 EventSource。
- 自动重连机制较简单。
- 可以利用事件 ID 实现增量补偿。

## 9.2 SSE 接口

```http
GET /api/v1/executions/{executionId}/events/stream
```

示例：

```text
id: 10
event: NODE_STARTED
data: {"nodeKey":"collect_repository","status":"RUNNING"}

id: 11
event: NODE_SUCCEEDED
data: {"nodeKey":"collect_repository","status":"SUCCEEDED"}

id: 12
event: AGENT_STARTED
data: {"nodeKey":"analyze_repository","status":"RUNNING"}
```

每个事件需要包含：

- id：事件序号。
- event：事件类型。
- data：JSON 数据。

## 9.3 断线重连

浏览器重新连接时可以携带：

```http
Last-Event-ID: 12
```

服务端根据事件序号查询后续事件：

```sql
SELECT *
FROM execution_event
WHERE workflow_execution_id = ?
  AND sequence_no > ?
ORDER BY sequence_no
LIMIT 100;
```

这样即使用户刷新页面或者网络断开，也可以补齐遗漏的进度事件。

注意：

SSE 只负责实时展示，不负责保存业务状态。

前端重新进入页面时，应先查询执行详情，再建立 SSE 连接。

如果历史事件已经被归档或超出保留窗口，应返回当前执行快照，并明确告知客户端从哪个 sequence_no 开始继续接收。

---

# 10. 错误码规范

建议统一错误码结构：

```text
模块前缀 + 错误类型 + 编号
```

示例：

| 错误码 | 说明 |
|---|---|
| WF-VALIDATION-001 | 工作流定义非法 |
| WF-CYCLE-001 | 检测到循环依赖 |
| WF-NOT-FOUND-001 | 工作流不存在 |
| EX-STATE-001 | 非法执行状态转换 |
| EX-LOCK-001 | 并发状态更新冲突 |
| EX-LEASE-001 | 执行租约失效 |
| EX-RETRY-001 | 重试次数超限 |
| MQ-PUBLISH-001 | 消息发布失败 |
| MQ-CONSUME-001 | 消息消费失败 |
| AG-INVOCATION-001 | Agent 调用不存在 |
| AG-TIMEOUT-001 | Agent 执行超时 |
| AG-CHECKPOINT-001 | Checkpoint 恢复失败 |
| AP-STATE-001 | 审批状态不合法 |
| RP-GENERATE-001 | 报告生成失败 |

错误信息需要区分：

- 面向用户的可读描述。
- 内部诊断信息。
- 可恢复性分类。
- 是否允许重试。

不要将数据库异常堆栈、模型内部错误或密钥信息直接返回给前端。

---

# 11. 消息协议版本管理

随着项目不断迭代，消息字段可能发生变化。

例如：

V1.0：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001"
}
```

V1.1 增加：

```json
{
  "invocationId": 30001,
  "threadId": "flowpilot-30001",
  "executionPolicy": {
    "maxToolCalls": 20
  }
}
```

建议遵循：

1. 新增可选字段尽量保持向后兼容。
2. 删除字段或改变字段含义属于不兼容变更。
3. 不兼容变更需要升级 schemaVersion。
4. 消费者必须明确处理不支持的版本。
5. 不允许静默忽略关键业务字段。
6. 生产者与消费者升级时采用兼容发布顺序。

第一阶段可以采用：

```text
schemaVersion = 1.0
```

后续根据实际变化升级。

建议将消息 Schema 放在独立目录：

```text
flowpilot-protocol/
│
├── common/
│   ├── envelope.schema.json
│   └── error.schema.json
│
├── workflow/
│   └── workflow-started.schema.json
│
├── node/
│   ├── node-execute.schema.json
│   ├── node-completed.schema.json
│   └── node-failed.schema.json
│
└── agent/
    ├── agent-execute.schema.json
    ├── agent-completed.schema.json
    ├── agent-failed.schema.json
    ├── agent-interrupted.schema.json
    ├── agent-resume.schema.json
    └── agent-cancel.schema.json
```

这样 Java 和 Python 可以共享同一套协议定义。

---

# 12. 安全设计

FlowPilot 的 Agent 需要调用外部工具，因此消息和接口安全不能忽视。

## 12.1 服务间身份验证

RabbitMQ 不应该允许任意外部服务连接。

建议：

- 为 Java Backend 和 Python Agent Service 创建独立账号。
- 为不同账号配置最小权限。
- 限制 Virtual Host 和 Queue 访问范围。
- 生产环境启用 TLS。
- 定期轮换凭据。

如果使用 REST 作为服务间通信，也必须验证服务身份。

## 12.2 消息完整性

第一阶段主要依靠 RabbitMQ 访问控制和 TLS。

如果后续需要跨不可信网络传输，可以增加消息签名或 HMAC。

签名时应明确规范化规则，避免 JSON 序列化差异导致验签失败。

## 12.3 输入安全

GitHub 仓库分析属于外部输入场景。

需要防范：

- 恶意仓库内容。
- README 中的提示词注入。
- 恶意 Issue 内容。
- SSRF。
- 恶意依赖包。
- 工具调用越权。

Agent 不应该直接获得任意网络访问和任意系统命令执行权限。

工具层应采用明确的 Allowlist。

例如：

```text
允许：
- 查询指定 GitHub 仓库公开信息
- 查询指定仓库 Commit
- 查询指定仓库 Issue
- 查询指定仓库 Release

禁止：
- 任意 URL 抓取
- 任意本地文件读取
- 任意 Shell 执行
- 任意数据库查询
```

对于仓库 URL，需要限制协议、域名、重定向和解析后的 IP 地址，防止访问内网地址及云元数据服务。

对于 Agent 生成的工具参数，必须在工具执行前进行独立校验，不能将模型输出视为可信输入。

---

# 13. 联调测试方案

Java 和 Python 开发完成后，必须进行跨服务联调。

建议按照以下顺序。

## 13.1 基础消息测试

测试：

1. Java 发布 AgentExecute。
2. Python 成功消费消息。
3. Python 返回 AgentCompleted。
4. Java 成功接收并更新业务状态。

## 13.2 幂等测试

模拟：

- AgentExecute 重复投递。
- AgentCompleted 重复发送。
- AgentResume 重复发送。
- Java 消费消息时数据库事务失败。
- RabbitMQ ACK 前服务崩溃。

检查是否出现重复执行。

## 13.3 Checkpoint 恢复测试

流程：

1. Agent 启动。
2. 执行若干步骤。
3. 保存 Checkpoint。
4. 模拟 Agent Service 崩溃。
5. 重启服务。
6. 根据原 threadId 恢复。
7. 验证是否从持久化状态继续执行。

## 13.4 人工审批测试

流程：

1. Agent 执行到审批节点。
2. 触发 Interrupt。
3. 保存 Checkpoint。
4. Java 创建审批记录。
5. 用户提交审批。
6. Java 发布 AgentResume。
7. Python 恢复执行。
8. 返回最终结果。

## 13.5 协议兼容测试

需要验证：

- 缺少必填字段。
- 字段类型错误。
- 不支持的 schemaVersion。
- payload 格式错误。
- 无效 traceId。
- 重复 messageId。

所有异常消息都应该得到明确处理，而不是让消费者无限重试。

---

# 14. 开发实施顺序

建议分成五个阶段。

## 第一阶段：协议基础

实现：

- MessageEnvelope。
- JSON Schema。
- 统一 ID 规范。
- 错误码规范。
- RabbitMQ Exchange 和 Queue。

验收：Java 和 Python 能够解析同一套消息协议。

## 第二阶段：普通节点通信

实现：

- WorkflowStarted。
- NodeExecute。
- NodeCompleted。
- NodeFailed。

验收：Java 能够通过 RabbitMQ 调度 HTTP 和 CODE 节点。

## 第三阶段：Agent 通信

实现：

- AgentExecute。
- AgentCompleted。
- AgentFailed。
- agent_invocation 状态同步。

验收：Java 能够启动 Python Agent，并接收执行结果。

## 第四阶段：Agent 恢复

实现：

- AgentInterrupted。
- AgentResume。
- AgentCancel。
- Checkpoint 查询。
- resumeRequestId 幂等。

验收：Agent 能够中断、等待审批并恢复。

## 第五阶段：前端通信与工程化

实现：

- REST API。
- SSE。
- Last-Event-ID。
- 错误码处理。
- 链路追踪。
- 安全验证。

验收：前端可以完整展示工作流执行过程。

---

# 15. 本阶段技术亮点

这一阶段最值得展示的技术点包括：

**1. 跨语言统一协议设计**

通过统一 Envelope 和 JSON Schema，使 Java 与 Python 在不共享运行时的情况下协同工作。

**2. 基于业务标识的消息幂等**

将 messageId、invocationId、attemptNo 和 resumeRequestId 分层设计，解决不同类型消息的重复处理问题。

**3. 基于 RabbitMQ 的异步 Agent 执行**

避免 Java 请求线程阻塞等待 LLM，提升系统资源利用率。

**4. 基于 Checkpoint 的 Agent 恢复协议**

让 Agent 在中断、审批和服务异常后继续执行。

**5. 基于 SSE 的实时执行事件推送**

将持久化事件与实时展示结合，支持断线重连和事件补偿。

**6. 基于 OpenTelemetry 的跨服务追踪**

将 Java、RabbitMQ 和 Python Agent Service 的调用链关联起来，提升问题定位能力。

---

# 16. 总结

FlowPilot 的通信协议设计，核心是建立 Java Backend、Python Agent Service 和 RabbitMQ 之间稳定、可追踪、可恢复的协作机制。

整体关系：

\[
\boxed{
REST + AMQP + SSE + JSON\ Schema
}
\]

其中：

- REST 负责用户业务请求。
- AMQP 负责服务间异步任务通信。
- SSE 负责前端实时进度展示。
- JSON Schema 负责跨语言协议约束。
- OpenTelemetry 负责跨服务链路追踪。

通过统一业务标识、消息版本、幂等键和状态同步规则，可以让 Java 工作流引擎与 Python Agent Runtime 独立开发，同时保持完整的业务协作能力。

最终目标是：

**Java 管理业务流程，Python 管理 Agent 执行，RabbitMQ 连接两个世界。**