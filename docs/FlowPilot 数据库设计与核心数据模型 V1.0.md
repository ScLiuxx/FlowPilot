# FlowPilot 数据库设计与核心数据模型 V1.0

**项目名称：** FlowPilot  
**文档版本：** V1.0  
**文档类型：** 数据库设计文档  
**核心数据库：** MySQL 8.0、PostgreSQL  
**适用阶段：** MVP 至第一阶段正式版本

---

# 1. 数据库设计目标

FlowPilot 是一个由传统工作流引擎与 Agent Runtime 协同组成的智能工作流执行平台。系统不仅需要存储用户、工作流和报告等常规业务数据，还需要完整记录长时间运行任务的执行状态、节点依赖关系、失败重试、人工审批以及 Agent 内部状态。

因此，数据库设计需要重点满足以下要求：

1. **执行过程可追踪：** 能够查询一次工作流执行的完整过程，包括每个节点的状态、开始时间、结束时间、输入输出和错误信息。
2. **任务状态可恢复：** 服务重启、消息重复投递、Worker 崩溃后，能够根据持久化数据恢复任务。
3. **执行过程可审计：** 保留状态变化、人工审批和关键操作记录。
4. **消息可靠投递：** 通过 Outbox 和消费幂等机制，降低数据库事务与消息队列之间的数据不一致风险。
5. **支持 Agent 协同：** 能够关联 Java 业务任务与 Python LangGraph 执行实例。
6. **便于扩展：** 支持后续增加工作流节点类型、Agent 类型、报告类型和执行策略。

数据库采用职责分离的设计：

| 数据库 | 主要职责 | 核心数据 |
|---|---|---|
| MySQL 8 | 业务数据与执行状态 | 用户、工作流、执行实例、节点执行、审批、报告 |
| PostgreSQL | Agent 内部状态持久化 | LangGraph Checkpoint、Checkpoint Writes |

其中，MySQL 是业务执行状态的权威数据源。PostgreSQL 只负责保存 Agent 图执行过程中的内部状态。

两者不采用跨数据库分布式事务，而是通过稳定的业务标识、消息幂等和恢复机制保证最终一致性。

# 2. 数据库整体模型

第一阶段采用以下核心数据表。

## 2.1 核心表清单

| 模块 | 表名 | 作用 | 优先级 |
|---|---|---|---|
| 用户模块 | sys_user | 用户基本信息 | P0 |
| 工作流模块 | workflow_definition | 工作流基本信息 | P0 |
| 工作流模块 | workflow_version | 工作流不可变版本 | P0 |
| 执行模块 | workflow_execution | 工作流执行实例 | P0 |
| 执行模块 | node_execution | 节点执行实例 | P0 |
| 执行模块 | node_execution_attempt | 节点执行尝试记录 | P0 |
| 执行模块 | execution_event | 执行事件记录 | P0 |
| 消息模块 | message_outbox | 待发送消息 | P0 |
| 消息模块 | message_consume_record | 消费幂等记录 | P1 |
| 审批模块 | approval_record | 人工审批记录 | P0 |
| Agent 模块 | agent_invocation | Agent 调用关联记录 | P0 |
| 报告模块 | analysis_report | 技术尽调报告 | P0 |

说明：

- P0 表示第一阶段必须实现。
- P1 表示可以在基础流程跑通后实现。
- 第一阶段不单独设计复杂的 RBAC 权限表，用户权限可以先通过简单角色字段实现，后续再扩展角色、权限和角色关联表。
- 不单独设计通用文件管理系统，报告文件可以先存储在本地或对象存储中，数据库保存文件元信息和存储地址。

# 3. 核心业务关系

## 3.1 实体关系

核心实体之间的关系如下：

```text
sys_user
   |
   | 1:N
   v
workflow_definition
   |
   | 1:N
   v
workflow_version
   |
   | 1:N
   v
workflow_execution
   |
   | 1:N
   v
node_execution
   |
   | 1:N
   v
node_execution_attempt

workflow_execution
   |
   +------ execution_event
   |
   +------ message_outbox
   |
   +------ analysis_report

node_execution
   |
   +------ approval_record
   |
   +------ agent_invocation
              |
              +------ LangGraph thread_id
              |
              +------ PostgreSQL Checkpoint
```

需要特别注意：

**workflow_definition 与 workflow_version 必须分离。**

工作流定义表示一个逻辑上的工作流，例如：

```text
GitHub 技术尽调工作流
```

工作流版本表示某个具体、不可变的执行配置，例如：

```text
GitHub 技术尽调工作流 V1
GitHub 技术尽调工作流 V2
```

一次工作流执行必须绑定一个确定的 workflow_version_id。

假设用户在 10:00 启动了 V1 工作流，10:05 管理员修改工作流并发布 V2，那么 10:00 启动的执行实例仍然必须按照 V1 运行。

否则，执行过程中的节点定义、依赖关系和参数可能发生变化，导致任务无法正确恢复，也无法准确审计。

因此，工作流版本发布后禁止直接修改其核心配置。

# 4. MySQL 核心表结构设计

以下采用 MySQL 8.0，统一使用 InnoDB 引擎。

主键使用 BIGINT 类型，应用层生成雪花 ID，避免依赖数据库自增 ID 带来的分布式扩展限制。

时间字段统一使用 DATETIME(3)，保存毫秒精度的时间信息，应用层统一使用 UTC 时间。

## 4.1 用户表 sys_user

保存平台用户信息。

```sql
CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    email VARCHAR(128),
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

字段说明：

| 字段 | 说明 |
|---|---|
| id | 用户 ID |
| username | 登录账号 |
| password_hash | 加密后的密码摘要 |
| nickname | 用户昵称 |
| email | 邮箱 |
| role | 用户角色 |
| status | 用户状态 |

密码不能明文存储，应使用 Argon2id 或 BCrypt 等密码哈希算法。

第一阶段角色可以采用：

- ADMIN：管理员
- USER：普通用户

后续再扩展完整 RBAC 权限模型。

## 4.2 工作流定义表 workflow_definition

保存工作流的基本信息。

```sql
CREATE TABLE workflow_definition (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    category VARCHAR(64),
    owner_id BIGINT NOT NULL,
    latest_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    KEY idx_owner_id (owner_id),
    KEY idx_category_status (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

工作流定义只保存工作流的基本信息，不直接保存可变的节点执行状态。

`latest_version` 表示当前最新发布版本。

`status` 可以使用：

- DRAFT：草稿
- PUBLISHED：已发布
- DISABLED：已禁用

## 4.3 工作流版本表 workflow_version

这是工作流模块最重要的表之一。

```sql
CREATE TABLE workflow_version (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    version INT NOT NULL,
    definition_json JSON NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    published_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_workflow_version (workflow_id, version),
    UNIQUE KEY uk_workflow_hash (workflow_id, definition_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

核心字段：

**definition_json：**

保存完整的工作流 DAG 定义。

示例：

```json
{
  "workflowId": "10001",
  "name": "GitHub技术尽调",
  "nodes": [
    {
      "id": "collect_repository",
      "type": "HTTP",
      "config": {
        "operation": "get_repository"
      },
      "dependencies": []
    },
    {
      "id": "collect_commits",
      "type": "HTTP",
      "config": {
        "operation": "get_commits"
      },
      "dependencies": [
        "collect_repository"
      ]
    },
    {
      "id": "collect_issues",
      "type": "HTTP",
      "config": {
        "operation": "get_issues"
      },
      "dependencies": [
        "collect_repository"
      ]
    },
    {
      "id": "analyze_repository",
      "type": "AGENT",
      "config": {
        "agentType": "REPOSITORY_ANALYST"
      },
      "dependencies": [
        "collect_commits",
        "collect_issues"
      ]
    },
    {
      "id": "human_review",
      "type": "HUMAN",
      "config": {
        "approvalType": "REPORT_REVIEW"
      },
      "dependencies": [
        "analyze_repository"
      ]
    }
  ]
}
```

**definition_hash：**

对规范化后的工作流 JSON 计算 SHA-256，作为版本内容校验标识。

注意：

不能简单地认为 JSON 字符串完全相同才代表定义相同。应先进行规范化处理，例如统一对象键顺序，再计算哈希。

版本发布后：

1. 禁止修改 definition_json。
2. 禁止修改 definition_hash。
3. 新增或修改节点时创建新版本。
4. 执行实例必须保存 workflow_version_id。

这里的不可变性主要由应用层发布流程和数据库权限共同保证，而不是仅靠一个 status 字段。

## 4.4 工作流执行表 workflow_execution

记录一次完整的工作流执行实例。

例如：

```text
用户提交 GitHub 仓库 URL
        |
        v
创建 workflow_execution
        |
        v
执行多个节点
        |
        v
生成尽调报告
```

表结构：

```sql
CREATE TABLE workflow_execution (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,

    execution_no VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,

    input_json JSON,
    output_json JSON,

    current_node_count INT NOT NULL DEFAULT 0,
    completed_node_count INT NOT NULL DEFAULT 0,
    failed_node_count INT NOT NULL DEFAULT 0,

    error_code VARCHAR(64),
    error_message TEXT,

    version BIGINT NOT NULL DEFAULT 0,

    started_at DATETIME(3),
    finished_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_execution_no (execution_no),
    KEY idx_user_created (user_id, created_at),
    KEY idx_workflow_status (workflow_id, status),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

关键字段说明：

| 字段 | 作用 |
|---|---|
| workflow_id | 所属工作流 |
| workflow_version_id | 本次执行使用的固定版本 |
| execution_no | 对外展示的执行编号 |
| status | 当前执行状态 |
| input_json | 工作流输入 |
| output_json | 工作流最终输出 |
| version | 乐观锁版本号 |
| started_at | 开始时间 |
| finished_at | 结束时间 |

状态定义：

```text
CREATED
QUEUED
RUNNING
WAITING_HUMAN
RETRY_WAIT
SUCCEEDED
FAILED
CANCELED
```

其中：

- CREATED：执行实例已创建。
- QUEUED：等待调度。
- RUNNING：至少存在正在执行的节点。
- WAITING_HUMAN：工作流等待人工操作。
- RETRY_WAIT：工作流处于等待重试状态。
- SUCCEEDED：所有必要节点执行成功。
- FAILED：工作流执行失败。
- CANCELED：工作流被取消。

这里的 `version` 用于乐观锁。

例如：

```sql
UPDATE workflow_execution
SET status = 'RUNNING',
    version = version + 1
WHERE id = ?
  AND version = ?
  AND status = 'QUEUED';
```

如果更新行数为 0，说明当前状态已经被其他线程修改，当前操作不能继续按照旧状态执行。

注意：乐观锁只能防止并发覆盖，不能替代完整的状态流转校验。

## 4.5 节点执行表 node_execution

记录工作流中每个节点的一次逻辑执行。

例如：

```text
workflow_execution
    |
    +-- collect_repository
    |
    +-- collect_commits
    |
    +-- collect_issues
    |
    +-- analyze_repository
    |
    +-- human_review
```

表结构：

```sql
CREATE TABLE node_execution (
    id BIGINT PRIMARY KEY,

    workflow_execution_id BIGINT NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,

    status VARCHAR(32) NOT NULL,

    input_json JSON,
    output_json JSON,

    current_attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,

    worker_id VARCHAR(128),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expire_at DATETIME(3),
    heartbeat_at DATETIME(3),

    error_code VARCHAR(64),
    error_message TEXT,

    version BIGINT NOT NULL DEFAULT 0,

    started_at DATETIME(3),
    finished_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_execution_node (
        workflow_execution_id,
        node_key
    ),

    KEY idx_status_lease (status, lease_expire_at),
    KEY idx_workflow_status (workflow_execution_id, status),
    KEY idx_worker_status (worker_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

这里有一个重要设计：

**node_execution 表示一次节点逻辑执行，而不是一次物理执行尝试。**

例如，一个节点执行失败后重试三次：

```text
node_execution
    |
    +-- attempt 1：FAILED
    |
    +-- attempt 2：FAILED
    |
    +-- attempt 3：SUCCEEDED
```

node_execution 只有一条记录，具体尝试过程保存在 node_execution_attempt 中。

这样可以避免重试时不断创建新的逻辑节点，导致依赖关系混乱。

### Lease 和 Fencing Token

节点执行采用租约机制。

Worker 获取任务后：

1. 记录 worker_id。
2. 更新 lease_expire_at。
3. 增加 lease_token。
4. 定期更新 heartbeat_at。
5. 完成任务时校验 lease_token。

假设 Worker A 获取节点时：

```text
worker_id = A
lease_token = 10
```

随后 Worker A 崩溃，租约过期。

Worker B 重新获取任务：

```text
worker_id = B
lease_token = 11
```

如果 Worker A 恢复运行并尝试提交旧结果，由于它持有的 token 是 10，系统拒绝其提交。

这就是 Fencing Token 的基本思想。

完成节点时必须执行类似校验：

```sql
UPDATE node_execution
SET status = 'SUCCEEDED',
    output_json = ?,
    finished_at = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE id = ?
  AND status = 'RUNNING'
  AND lease_token = ?
  AND current_attempt = ?;
```

实际实现还需要校验当前 Attempt 状态，并在同一个 MySQL 事务中完成节点结果、Attempt 结果和事件记录的更新。

## 4.6 节点执行尝试表 node_execution_attempt

用于记录每次真实执行过程。

```sql
CREATE TABLE node_execution_attempt (
    id BIGINT PRIMARY KEY,

    node_execution_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,

    status VARCHAR(32) NOT NULL,

    worker_id VARCHAR(128),
    lease_token BIGINT NOT NULL,

    input_snapshot JSON,
    output_snapshot JSON,

    error_code VARCHAR(64),
    error_message TEXT,

    started_at DATETIME(3),
    finished_at DATETIME(3),

    duration_ms BIGINT,

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_node_attempt (
        node_execution_id,
        attempt_no
    ),

    KEY idx_node_status (node_execution_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

为什么需要单独设计 Attempt？

因为实际运行中，失败本身就是重要的工程数据。

例如：

```text
Attempt 1
    ERROR: GITHUB_API_TIMEOUT

Attempt 2
    ERROR: RATE_LIMIT_EXCEEDED

Attempt 3
    SUCCESS
```

通过 Attempt 可以分析：

- 某个节点平均需要重试多少次。
- 哪类节点最容易失败。
- Agent 调用失败率。
- API 超时情况。
- 不同执行策略的性能差异。

此外，Attempt 可以记录输入快照，方便问题复现。

但不建议无限制保存所有大型输入输出。对于大体积数据，应保存对象存储地址或数据引用。

## 4.7 执行事件表 execution_event

用于保存执行过程中的关键事件。

```sql
CREATE TABLE execution_event (
    id BIGINT PRIMARY KEY,

    workflow_execution_id BIGINT NOT NULL,
    node_execution_id BIGINT,

    event_type VARCHAR(64) NOT NULL,
    event_data JSON,

    sequence_no BIGINT NOT NULL,

    operator_id BIGINT,
    trace_id VARCHAR(64),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_execution_sequence (
        workflow_execution_id,
        sequence_no
    ),

    KEY idx_execution_created (
        workflow_execution_id,
        created_at
    ),

    KEY idx_node_created (
        node_execution_id,
        created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

事件类型示例：

```text
WORKFLOW_CREATED
WORKFLOW_STARTED
NODE_READY
NODE_STARTED
NODE_SUCCEEDED
NODE_FAILED
NODE_RETRY
AGENT_STARTED
AGENT_INTERRUPTED
APPROVAL_SUBMITTED
WORKFLOW_SUCCEEDED
WORKFLOW_FAILED
WORKFLOW_CANCELED
```

为什么需要 Event 表？

当前状态表只能告诉我们：

```text
节点状态：SUCCEEDED
```

但无法直接回答：

- 节点什么时候开始执行？
- 为什么重试？
- 哪个 Worker 执行失败？
- 用户什么时候批准？
- Agent 什么时候触发中断？

事件表则保留这些历史信息。

### Sequence No 设计

每次执行事件分配一个递增的 sequence_no。

例如：

```text
1 WORKFLOW_CREATED
2 WORKFLOW_STARTED
3 NODE_STARTED
4 NODE_SUCCEEDED
5 AGENT_STARTED
6 AGENT_INTERRUPTED
7 APPROVAL_SUBMITTED
8 WORKFLOW_SUCCEEDED
```

Sequence No 可用于前端 SSE 断线重连后的事件补偿。

第一阶段可以在 workflow_execution 中增加一个 `event_sequence` 字段，在同一事务内锁定执行实例、递增序号并插入事件。

这样能够保证同一工作流执行实例内的事件序号唯一。

注意：事件表是业务审计与进度事件记录，不是完整的事件溯源架构。系统的权威当前状态仍然保存在 workflow_execution 和 node_execution 中。

# 5. 消息可靠性相关表

## 5.1 Outbox 表 message_outbox

RabbitMQ 和 MySQL 是两个独立系统。

假设业务代码执行：

```text
1. 更新 node_execution 状态
2. 向 RabbitMQ 发送消息
```

可能出现：

- 数据库更新成功，但消息发送失败。
- 消息发送成功，但数据库事务回滚。

因此不能简单地将数据库更新与消息发送当成一个原子操作。

FlowPilot 采用 Transactional Outbox 模式。

```sql
CREATE TABLE message_outbox (
    id BIGINT PRIMARY KEY,

    message_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(64) NOT NULL,

    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,

    routing_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3),

    UNIQUE KEY uk_message_id (message_id),

    KEY idx_status_retry (
        status,
        next_retry_at,
        created_at
    ),

    KEY idx_aggregate (
        aggregate_type,
        aggregate_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

发送流程：

```text
Java 业务线程
      |
      v
开启 MySQL 事务
      |
      +---- 更新业务状态
      |
      +---- 插入 message_outbox
      |
      v
提交事务
      |
      v
Outbox Publisher 扫描待发送记录
      |
      v
发送 RabbitMQ
      |
      v
收到 Publisher Confirm
      |
      v
更新 Outbox 状态为 PUBLISHED
```

这里必须明确：

**Outbox 不能保证消息只发送一次。**

例如：

1. Publisher 成功发送消息。
2. RabbitMQ 已经收到消息。
3. Publisher 在更新 Outbox 状态之前崩溃。
4. 重启后重新发送同一消息。

因此消费者必须实现幂等。

Outbox Publisher 可以使用 `FOR UPDATE SKIP LOCKED` 领取待发送记录，避免多个 Publisher 同时处理同一批数据。

示意：

```sql
SELECT id
FROM message_outbox
WHERE status = 'PENDING'
  AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

实际发送不应长时间占用数据库事务。可以先通过短事务领取记录并设置处理中状态，再发送消息，最终根据 Publisher Confirm 更新状态。处理中记录需要有超时回收机制。

## 5.2 消费幂等表 message_consume_record

第一阶段可以先依靠业务唯一约束和状态条件更新完成核心幂等。

如果需要通用的消费幂等记录，可以增加：

```sql
CREATE TABLE message_consume_record (
    id BIGINT PRIMARY KEY,

    consumer_name VARCHAR(128) NOT NULL,
    message_id VARCHAR(64) NOT NULL,

    status VARCHAR(32) NOT NULL,
    consumed_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_consumer_message (
        consumer_name,
        message_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

消费流程：

```text
收到 RabbitMQ 消息
       |
       v
开启数据库事务
       |
       v
尝试插入消费记录
       |
       +---- 已存在：说明可能重复消费
       |
       +---- 不存在：继续业务处理
       |
       v
更新业务状态
       |
       v
提交数据库事务
       |
       v
ACK 消息
```

消费记录和业务处理必须在同一个 MySQL 事务内完成。

如果业务处理失败并回滚，消费记录也必须回滚，不能提前标记为成功。

另外，不能只依赖 message_id。

对于节点执行消息，还应通过业务执行标识判断：

```text
workflow_execution_id
+
node_execution_id
+
attempt_no
```

这样即使生产者因为异常重新生成了 message_id，也不会导致同一次业务执行被重复处理。

# 6. Agent 协同数据模型

## 6.1 Agent 调用记录 agent_invocation

Java 业务层需要知道 Agent 执行到什么阶段，但不应该直接管理 LangGraph 的全部内部状态。

因此，单独设计 Agent 调用关联表。

```sql
CREATE TABLE agent_invocation (
    id BIGINT PRIMARY KEY,

    invocation_no VARCHAR(64) NOT NULL,

    workflow_execution_id BIGINT NOT NULL,
    node_execution_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,

    agent_type VARCHAR(64) NOT NULL,

    thread_id VARCHAR(128) NOT NULL,
    checkpoint_id VARCHAR(128),

    status VARCHAR(32) NOT NULL,

    model_name VARCHAR(128),
    token_input BIGINT NOT NULL DEFAULT 0,
    token_output BIGINT NOT NULL DEFAULT 0,

    error_code VARCHAR(64),
    error_message TEXT,

    started_at DATETIME(3),
    finished_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_invocation_no (invocation_no),
    UNIQUE KEY uk_thread_id (thread_id),

    KEY idx_node_attempt (
        node_execution_id,
        attempt_no
    ),

    KEY idx_workflow_execution (
        workflow_execution_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

核心字段：

| 字段 | 含义 |
|---|---|
| invocation_no | Agent 调用业务编号 |
| workflow_execution_id | 所属工作流执行 |
| node_execution_id | 所属节点执行 |
| attempt_no | 对应节点执行尝试次数 |
| thread_id | LangGraph 线程标识 |
| checkpoint_id | 最近一次已确认的 Checkpoint |
| status | Agent 调用状态 |
| token_input | 输入 Token 数量 |
| token_output | 输出 Token 数量 |

### 业务 ID 映射关系

FlowPilot 必须建立稳定的映射关系：

```text
workflow_execution_id
        |
        v
node_execution_id
        |
        v
attempt_no
        |
        v
agent_invocation_id
        |
        v
LangGraph thread_id
        |
        v
checkpoint_id
```

这里有一个关键区别：

- workflow_execution_id 表示业务工作流的一次执行。
- node_execution_id 表示某个节点的一次逻辑执行。
- attempt_no 表示该节点的第几次执行尝试。
- agent_invocation_id 表示一次 Agent 调用。
- thread_id 表示 LangGraph 的持久化执行线程。
- checkpoint_id 表示某个时间点保存的 Agent 状态。

同一次 Agent 调用恢复执行时，应继续使用原来的 thread_id。

如果业务明确要求重新执行，则创建新的 invocation，并根据执行策略决定使用新的 thread_id。

不能将一次 Agent 恢复错误地处理为一次全新的 Agent 调用。

## 6.2 PostgreSQL Checkpoint

LangGraph 的 Checkpoint 使用 PostgreSQL 持久化。

建议采用 LangGraph 官方 PostgreSQL Checkpoint 实现提供的表结构，不自行重造 Checkpoint 存储机制。

逻辑关系：

```text
MySQL
  |
  +-- agent_invocation
          |
          +-- thread_id
          |
          +-- checkpoint_id

PostgreSQL
  |
  +-- checkpoints
  |
  +-- checkpoint_blobs
  |
  +-- checkpoint_writes
```

MySQL 保存业务关联信息。

PostgreSQL 保存 Agent 内部状态。

两者之间通过 thread_id、checkpoint_id 和 invocation_id 建立关联。

注意：

MySQL 中的 checkpoint_id 不一定始终等于 PostgreSQL 当前最新的 Checkpoint。

如果 Agent 服务已经成功写入 Checkpoint，但尚未通知 Java 服务就崩溃，那么 PostgreSQL 中可能存在 MySQL 尚未记录的最新 Checkpoint。

因此，恢复时不能只相信 MySQL 保存的 checkpoint_id。

正确做法是：

1. Java 根据 agent_invocation 获取 thread_id。
2. Agent Service 根据 thread_id 查询实际 Checkpoint。
3. 检查业务执行状态和 Checkpoint 是否匹配。
4. 判断继续恢复、重新执行还是进入人工处理。
5. 将恢复后的最新状态同步回 MySQL。

这也是为什么两套数据库不能简单地假设具有强一致性。

# 7. 人工审批数据模型

## 7.1 approval_record

人工审批是 FlowPilot 区别于普通自动化脚本的重要功能。

```sql
CREATE TABLE approval_record (
    id BIGINT PRIMARY KEY,

    workflow_execution_id BIGINT NOT NULL,
    node_execution_id BIGINT NOT NULL,

    approval_type VARCHAR(64) NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    title VARCHAR(256),
    description TEXT,

    request_data JSON,
    decision_data JSON,

    requested_at DATETIME(3),
    decided_at DATETIME(3),

    approver_id BIGINT,
    comment TEXT,

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    KEY idx_approver_status (
        approver_id,
        status
    ),

    KEY idx_execution_status (
        workflow_execution_id,
        status
    ),

    KEY idx_node_status (
        node_execution_id,
        status
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

审批状态：

```text
PENDING
APPROVED
REJECTED
CANCELED
EXPIRED
```

审批流程：

```text
Agent 执行
    |
    v
LangGraph Interrupt
    |
    v
保存 Checkpoint
    |
    v
Java 收到 AgentInterrupted
    |
    v
创建 approval_record
    |
    v
工作流进入 WAITING_HUMAN
    |
    v
用户审批
    |
    +---- APPROVED
    |         |
    |         v
    |     AgentResume
    |
    +---- REJECTED
              |
              v
          根据策略结束或退回
```

需要注意：

审批操作本身也必须具备幂等性。

例如，用户连续点击两次批准按钮，系统只能接受一次有效审批。

可以通过事务、状态条件更新以及唯一的审批操作标识实现。

对于审批通过后的恢复操作，应在同一事务中完成审批状态更新和恢复消息 Outbox 写入。

# 8. 技术尽调报告数据模型

## 8.1 analysis_report

保存最终生成的技术尽调报告。

```sql
CREATE TABLE analysis_report (
    id BIGINT PRIMARY KEY,

    workflow_execution_id BIGINT NOT NULL,

    report_no VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,

    repository_url VARCHAR(512),

    report_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,

    summary TEXT,

    content_json JSON,
    markdown_content MEDIUMTEXT,

    storage_url VARCHAR(1024),
    content_hash CHAR(64),

    generated_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_report_no (report_no),
    KEY idx_execution (workflow_execution_id),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

报告可以同时保存：

- Markdown 原始内容。
- 结构化 JSON。
- 最终导出的 HTML 或 PDF 文件地址。
- 内容哈希。

对于体积较大的报告文件，建议存入对象存储，不直接将文件二进制内容写入 MySQL。

报告状态：

```text
GENERATING
PENDING_REVIEW
APPROVED
REJECTED
PUBLISHED
FAILED
```

报告审核状态和工作流执行状态需要分开管理。

例如：

工作流已经执行成功，不代表报告已经审核通过。

# 9. 关键事务设计

数据库设计不仅要确定有哪些表，还要明确关键业务操作如何保证一致性。

## 9.1 创建工作流执行实例

用户启动工作流时：

```text
1. 校验用户权限
2. 获取已发布的 workflow_version
3. 校验输入参数
4. 创建 workflow_execution
5. 创建初始 node_execution
6. 写入 WORKFLOW_CREATED 事件
7. 插入 WorkflowStarted Outbox 消息
8. 提交事务
```

其中 4～7 必须在同一个 MySQL 事务中完成。

事务提交后，由 Outbox Publisher 将任务发送到 RabbitMQ。

## 9.2 节点执行成功

Worker 完成节点后：

```text
开启事务
    |
    v
校验节点状态、Attempt 和 lease_token
    |
    v
更新 node_execution
    |
    v
更新 node_execution_attempt
    |
    v
写入 NODE_SUCCEEDED 事件
    |
    v
更新 workflow_execution 统计信息
    |
    v
提交事务
    |
    v
后续节点进入调度流程
```

这里不建议直接在节点完成事务中执行复杂的 DAG 调度。

更合理的方式是：

1. 当前节点成功后写入状态和事件。
2. 通过 Outbox 发布 NodeCompleted。
3. Scheduler 消费事件。
4. 查询该节点的后继节点。
5. 检查后继节点的全部依赖是否满足。
6. 满足条件的节点进入 READY。
7. 生成对应执行消息。

这样可以将状态持久化和调度逻辑解耦。

## 9.3 节点失败与重试

失败时：

```text
Attempt 1 FAILED
      |
      v
判断是否允许重试
      |
      +---- 是
      |      |
      |      v
      |  设置 RETRY_WAIT
      |      |
      |      v
      |  计算 next_retry_at
      |      |
      |      v
      |  后续重新投递
      |
      +---- 否
             |
             v
          FAILED
```

建议采用指数退避：

\[
T_n=\min(T_{\max},T_0\cdot 2^{n-1})
\]

其中：

- \(T_0\)：初始等待时间。
- \(T_{\max}\)：最大等待时间。
- \(n\)：当前重试次数。

可以增加随机抖动，避免大量任务同时重试造成系统压力。

重试策略不能只根据异常类型判断。

例如：

| 异常 | 建议策略 |
|---|---|
| 网络超时 | 有限次数重试 |
| GitHub API 限流 | 根据 Retry-After 延迟 |
| 参数校验失败 | 不重试 |
| 权限不足 | 不重试 |
| LLM 服务暂时不可用 | 有限次数重试 |
| Agent 工具执行异常 | 根据工具副作用和错误类型决定 |

特别注意：有副作用的工具调用不能简单地重复执行。

例如，发送邮件、创建 GitHub Issue、修改仓库配置等操作，需要独立的业务幂等键或补偿机制。

## 9.4 人工审批事务

用户提交审批结果时：

```text
开启事务
    |
    v
校验 approval_record 当前状态
    |
    v
更新审批结果
    |
    v
写入 APPROVAL_SUBMITTED 事件
    |
    v
插入 AgentResume Outbox
    |
    v
提交事务
```

Agent Service 收到恢复消息后，使用原来的 thread_id 恢复 LangGraph 执行。

如果恢复消息重复投递，Agent Service 必须通过 invocation 状态和恢复请求标识进行幂等处理。

# 10. 数据库索引设计

第一阶段主要考虑三类查询：

1. 用户查询自己的工作流执行记录。
2. Scheduler 查询可以执行或需要恢复的节点。
3. 运维人员查询失败任务和执行事件。

核心索引如下：

| 表 | 索引 | 主要用途 |
|---|---|---|
| workflow_definition | owner_id | 查询用户工作流 |
| workflow_version | workflow_id, version | 查询工作流版本 |
| workflow_execution | user_id, created_at | 分页查询执行历史 |
| workflow_execution | status, created_at | 查询待处理执行 |
| node_execution | status, lease_expire_at | 恢复过期节点 |
| node_execution | workflow_execution_id, status | 查询工作流节点状态 |
| node_execution_attempt | node_execution_id, attempt_no | 查询执行尝试 |
| execution_event | workflow_execution_id, sequence_no | 查询执行事件 |
| message_outbox | status, next_retry_at, created_at | 查询待发送消息 |
| agent_invocation | thread_id | 查询 Agent 执行状态 |
| approval_record | approver_id, status | 查询待审批事项 |
| analysis_report | workflow_execution_id | 查询执行报告 |

索引设计原则：

- 优先覆盖真实查询条件。
- 避免为每个字段都创建索引。
- 注意联合索引最左前缀原则。
- 高频更新字段不宜无节制地建立索引。
- 对 JSON 内部字段的查询，只有在确实存在需求时才考虑生成列或函数索引。

对于执行历史分页，建议使用基于创建时间和 ID 的游标分页，避免大偏移量分页带来的性能问题。

# 11. 数据一致性与故障恢复

这是整个数据库设计中最重要的部分。

FlowPilot 不是简单的 CRUD 应用。它需要面对多个服务、消息队列和两个数据库之间的状态协调问题。

## 11.1 两套数据库的职责边界

```text
                MySQL
                  |
       +----------+-----------+
       |          |           |
   工作流状态   节点状态    审批状态
       |          |           |
       +----------+-----------+
                  |
             Agent 调用记录
                  |
                  v
             Agent Service
                  |
                  v
              LangGraph
                  |
                  v
             PostgreSQL
                  |
             Checkpoint
```

不能出现以下设计：

- Java 直接修改 LangGraph Checkpoint。
- PostgreSQL Checkpoint 决定整个工作流的业务状态。
- MySQL 和 PostgreSQL 之间依赖跨库事务。
- Agent 失败后只检查 MySQL 状态，不检查实际 Checkpoint。

## 11.2 Worker 崩溃恢复

假设某个节点正在执行：

```text
status = RUNNING
worker_id = worker-01
lease_expire_at = 10:05:00
```

如果 Worker 崩溃且没有继续发送心跳，恢复扫描器发现租约过期。

处理流程：

```text
Recovery Scheduler
        |
        v
查询租约过期节点
        |
        v
确认节点仍处于 RUNNING
        |
        v
更新执行状态
        |
        v
增加 lease_token
        |
        v
根据节点类型决定恢复策略
        |
        +---- 普通节点：重新执行
        |
        +---- Agent 节点：检查 Checkpoint
        |
        +---- 人工审批节点：保持等待或检查审批状态
```

恢复时必须注意：

不能因为租约过期就直接创建新的执行实例。

应该恢复原来的 node_execution 和 attempt，或者按照明确的重试策略创建新的 attempt。

## 11.3 任务状态与事件状态

状态表保存当前状态。

事件表保存历史变化。

例如：

```text
node_execution.status = SUCCEEDED
```

而 execution_event 可能记录：

```text
NODE_READY
NODE_STARTED
NODE_FAILED
NODE_RETRY
NODE_STARTED
NODE_SUCCEEDED
```

如果系统重启，可以通过当前状态表恢复执行状态。

如果用户需要查看执行历史，则查询事件表。

这种设计兼顾了查询效率与审计能力。

# 12. 数据生命周期管理

工作流执行可能产生大量数据。

例如：

- 高频执行事件。
- 节点输入输出快照。
- LLM 请求和响应。
- Agent Checkpoint。
- 报告内容。

不能默认所有数据永久保留。

第一阶段建议：

| 数据 | 建议策略 |
|---|---|
| 用户信息 | 长期保留 |
| 工作流定义 | 长期保留 |
| 已发布工作流版本 | 长期保留 |
| 执行实例 | 根据业务需求保留 |
| 节点执行记录 | 与执行实例保持一致 |
| Attempt | 保留必要的失败和性能信息 |
| 执行事件 | 定期归档 |
| Outbox | 成功发送后延迟清理 |
| 消费记录 | 保留一定时间，避免重复消费窗口内记录丢失 |
| Agent Checkpoint | 根据恢复和审计要求清理 |
| 报告 | 根据业务要求长期保存 |

具体保留时间应在系统运行后根据数据量、审计要求和存储成本确定，不建议在 MVP 阶段过早制定复杂的分区和归档体系。

尤其需要注意：

Outbox 和消费幂等记录不能在消息仍可能重复投递时提前删除。

# 13. 数据库设计中的关键工程原则

总结 FlowPilot 的数据库设计，需要牢牢把握以下几个原则。

### 13.1 业务状态与执行历史分离

当前状态保存在业务表，历史变化保存在事件表。

不要为了记录历史而让业务表不断堆积状态字段。

### 13.2 逻辑执行与物理尝试分离

node_execution 表示一次逻辑节点执行。

node_execution_attempt 表示实际执行次数。

失败重试不应该破坏节点依赖关系。

### 13.3 工作流定义与工作流版本分离

已发布版本不可变。

执行实例绑定固定版本。

保证执行过程可复现。

### 13.4 消息发送与业务事务解耦

通过 Transactional Outbox 解决数据库和消息队列之间的双写问题。

通过消费幂等应对消息重复投递。

### 13.5 Agent 状态与业务状态分离

MySQL 管理业务执行。

PostgreSQL 管理 LangGraph Checkpoint。

通过 thread_id 和 invocation_id 关联。

### 13.6 任务恢复必须考虑旧 Worker

使用租约和 Fencing Token 防止过期 Worker 提交旧结果。

### 13.7 不盲目追求 Exactly Once

在分布式系统中，消息可能重复投递。

更现实的目标是：

**At-least-once Delivery + Idempotent Processing。**

即至少一次投递，加上业务幂等处理，实现业务效果上的一次性。

# 14. 第一阶段数据库实施顺序

建议按照以下顺序实施，而不是一次性把全部表都写完。

**第一阶段：基础业务模型**

实现：

- sys_user
- workflow_definition
- workflow_version
- workflow_execution
- node_execution

目标：能够创建工作流、发布版本并启动执行。

**第二阶段：执行可靠性**

实现：

- node_execution_attempt
- execution_event
- message_outbox

目标：支持并行执行、失败重试、消息可靠投递和任务状态恢复。

**第三阶段：Agent 协同**

实现：

- agent_invocation
- PostgreSQL Checkpoint

目标：Java 能够启动 Agent、查询执行状态并恢复 Agent。

**第四阶段：人工审批与报告**

实现：

- approval_record
- analysis_report

目标：完成 Agent 中断、人工审核、恢复执行和报告生成。

**第五阶段：工程化完善**

实现：

- message_consume_record
- 数据归档
- 监控统计
- 索引优化
- 数据清理策略

目标：提升系统可靠性和可维护性。

# 15. 本阶段验收标准

数据库设计完成后，需要能够通过以下测试：

1. 创建工作流，并成功发布不可变版本。
2. 修改工作流后生成新版本，旧执行实例仍绑定原版本。
3. 创建工作流执行实例，并正确初始化节点。
4. 多个无依赖节点能够并行执行。
5. 节点失败后能够创建新的 Attempt 并按策略重试。
6. RabbitMQ 重复投递时不会重复产生业务执行效果。
7. Worker 崩溃后能够通过租约过期检测恢复任务。
8. 旧 Worker 不能提交已经失效的执行结果。
9. Agent 调用能够关联 LangGraph thread_id。
10. Agent 中断后能够通过 Checkpoint 恢复。
11. 人工重复审批不会造成重复恢复。
12. 执行事件能够按 sequence_no 查询。
13. 工作流执行成功后能够生成关联报告。

---

# 16. 总结

FlowPilot 的数据库设计并不是简单地为几个业务模块创建数据表，而是围绕长时间运行任务的可靠执行建立一套持久化模型。

其中最重要的四组关系是：

1. workflow_definition 与 workflow_version：解决工作流版本不可变问题。
2. node_execution 与 node_execution_attempt：解决逻辑执行和物理执行尝试的分离问题。
3. message_outbox 与 message_consume_record：解决分布式消息的可靠投递与幂等消费问题。
4. agent_invocation 与 LangGraph Checkpoint：解决 Java 业务任务与 Python Agent 内部状态的协同问题。

这些设计共同支撑 FlowPilot 的核心能力：

**任务可追踪、执行可恢复、消息可重试、操作可审计、Agent 可中断、人工可介入。**

这也是项目相较于普通 Spring Boot CRUD 系统最值得深入实现和展示的工程部分。