# FlowPilot

**智能工作流与 Agent 协同执行平台**

FlowPilot 是一个面向复杂业务任务的智能工作流执行平台。传统工作流引擎管理确定性任务，
Agent Runtime 处理需要动态规划和智能决策的任务，两者通过统一的消息协议协作。

产品的第一个完整场景是 **开源软件项目智能技术尽调**：用户提交一个 GitHub 仓库地址，
平台自动采集数据、统计数据、由 Agent 完成分析、等待人工审核，最后生成结构化技术尽调报告。

---

## 目录结构

```text
flowpilot/
├── backend/              Java 21 + Spring Boot 3 模块化单体（业务控制面 + 执行引擎）
├── agent-service/        Python + FastAPI + LangGraph（Agent Runtime）
├── frontend/             Vue 3 + TypeScript 管理控制台
├── flowpilot-protocol/   跨语言消息协议（JSON Schema，Java 与 Python 共用）
├── db/                   数据库脚本
├── deploy/               部署配置
├── docs/                 接口契约文档
└── scripts/              本地开发与验证脚本
```

---

## 架构

```text
                    Vue 3 控制台
                        |
                   HTTP / SSE
                        |
              Spring Boot Backend (模块化单体)
                        |
        +---------------+----------------+
        |               |                |
   用户与鉴权       工作流模块        执行引擎
                        |                |
                        +-------+--------+
                                |
                      DAG 解析 / 状态机 / 调度器
                                |
              +-----------------+-----------------+
              |                 |                 |
          MySQL 8          Redis 7          Transactional Outbox
       业务状态权威源      缓存与协调              |
                                                   |
                                              RabbitMQ
                                                   |
                          +------------------------+------------------------+
                          |                        |                        |
                   Java Worker              Python Agent Service       Java Worker
                   HTTP / CODE              LangGraph + LLM          LangGraph 协调
                   CONDITION / MERGE              |
                          |                  PostgreSQL Checkpoint
                          +------------------------+------------------------+
                                                   |
                                        Execution Events (MySQL)
                                                   |
                                              SSE 实时推送
```

### 三个存储的职责

| 存储 | 职责 | 关键约束 |
|---|---|---|
| MySQL 8 | 业务数据与执行状态 | 权威来源，状态转换必须经过统一的状态管理服务 |
| Redis 7 | 缓存与短期协调 | 丢失后必须能从 MySQL 恢复核心业务状态 |
| PostgreSQL | LangGraph Checkpoint | 仅保存 Agent 内部执行状态，不决定业务状态 |

MySQL 与 PostgreSQL 之间**没有**跨库事务。跨服务的一致性通过稳定业务 ID、Outbox、
幂等事件和恢复扫描实现最终一致。

---

## 核心技术设计

### 1. 基于 DAG 的并行工作流调度

Kahn 拓扑排序用于环检测，时间复杂度 O(V+E)。拓扑排序只用于证明图无环，
调度器保留完整的依赖关系，因此没有依赖关系的节点可以真正并行执行。

工作流定义与版本分离：一次执行始终绑定一个不可变版本，即使工作流在执行期间被修改并重新发布。

### 2. 基于状态机的任务生命周期

工作流状态：`CREATED → QUEUED → RUNNING → (WAITING_HUMAN | RETRY_WAIT) → SUCCEEDED | FAILED | CANCELED`

节点状态：`PENDING → READY → RUNNING → (RETRY_WAIT | WAITING_HUMAN) → SUCCEEDED | FAILED | SKIPPED | CANCELED`

所有状态变更必须经过统一的状态管理服务，数据库层使用乐观锁保证并发安全。
`WAITING_HUMAN` 是正常的暂停状态，不是失败。

### 3. 基于 Transactional Outbox 的可靠消息投递

业务状态更新与 Outbox 记录在同一个 MySQL 事务中提交，发布器扫描 Outbox 并通过
Publisher Confirm 确认后才标记为已发送。这消除了数据库与消息队列之间的双写问题。

Outbox **不保证**消息只发送一次，因此消费者必须幂等。

### 4. 基于幂等键和唯一约束的重复消息处理

| 消息 | 幂等依据 |
|---|---|
| `WorkflowStarted` | `workflowExecutionId` |
| `NodeExecute` / `NodeCompleted` / `NodeFailed` | `nodeExecutionId` + `attemptNo` |
| `AgentExecute` / `AgentCompleted` / `AgentFailed` | `invocationId` |
| `AgentResume` | `invocationId` + `resumeRequestId` |

消息级去重（`message_consume_record`）与业务级幂等（条件状态更新、租约、fencing token）
是两层不同的防护，缺一不可。

### 5. 基于 Lease 和 Fencing Token 的 Worker 故障恢复

Worker 获取节点时记录 `worker_id`、递增 `lease_token`、设置 `lease_expire_at`，并定期续租。
租约过期后恢复扫描器接管任务。旧 Worker 即使恢复运行，其 `lease_token` 已失效，
所有结果提交都会被条件更新拒绝。

```sql
UPDATE node_execution SET status = 'SUCCEEDED', ...
 WHERE id = ? AND status = 'RUNNING' AND current_attempt = ? AND lease_token = ?
```

### 6. 基于 LangGraph Checkpoint 的 Agent 中断与恢复

`agent_invocation` 记录 Java 侧的业务关联，`thread_id` 固定为 `flowpilot-{invocationId}`。
消息重投时**绝不**重新生成 thread id，否则 Agent 会丢失 checkpoint 并从头开始。

LangGraph 的 interrupt 恢复会重新执行触发中断的节点，因此中断前的副作用必须幂等。
Agent 侧通过带确定性键的幂等日志保证审批请求只登记一次。

### 7. 跨语言统一协议

Java 与 Python 加载**同一份** JSON Schema：

```text
flowpilot-protocol/
├── common/envelope.schema.json
├── common/error.schema.json
├── workflow/workflow-started.schema.json
├── node/{node-execute,node-completed,node-failed}.schema.json
└── agent/{agent-execute,agent-completed,agent-failed,agent-interrupted,agent-resume,agent-cancel}.schema.json
```

Java 侧的 `ProtocolContractTest` 用这些文件校验自己产生的消息，
Python 侧在运行时校验收发消息。协议漂移会在测试阶段暴露，而不是在生产环境。

### 8. 安全

- JWT 身份认证，密码使用 BCrypt。
- 用户数据隔离，工作流与执行记录按用户过滤。
- **SSRF 防护**：只允许 `https://api.github.com`，禁止跟随重定向，解析后的 IP 必须是公网地址。
- **提示词注入防护**：仓库内容（README、Issue）作为不可信数据传给模型，不作为指令。
- **工具白名单**：Agent 只能调用明确注册的 GitHub 工具，不能执行 Shell 或读取任意本地文件。
- 外部操作使用幂等键，避免重复副作用。

---

## 本地运行

### 前置条件

Docker 与 Docker Compose，或者本机的 MySQL 8、Redis 7、RabbitMQ 3、PostgreSQL 16。

### 一键启动

```bash
docker compose up -d
```

| 服务 | 地址 |
|---|---|
| 控制台 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| API 文档 | http://localhost:8080/swagger-ui.html |
| RabbitMQ 管理台 | http://localhost:15672 (flowpilot / flowpilot) |

首次启动时 Flyway 会自动创建表结构，并发布一个内置的 **GitHub 技术尽调** 工作流。

### 不使用 Docker 的本地开发

```bash
# 1. 基础设施
bash scripts/dev-services.sh start

# 2. 后端
cd backend && mvn spring-boot:run

# 3. Agent 服务
cd agent-service && python -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --port 8000

# 4. 控制台
cd frontend && npm install && npm run dev
```

### 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥，生产环境必须修改 | 开发默认值 |
| `GITHUB_TOKEN` | 提升 GitHub API 速率限制 | 空 |
| `LLM_API_KEY` | 模型密钥，为空时使用确定性本地模型 | 空 |
| `MYSQL_HOST` / `REDIS_HOST` / `RABBITMQ_HOST` / `POSTGRES_HOST` | 基础设施地址 | localhost |

---

## 验证

```bash
# 后端单元测试与集成测试
cd backend && mvn test

# Agent 服务测试
cd agent-service && .venv/bin/python -m pytest tests -q

# 控制台类型检查与构建
cd frontend && npm run build

# 端到端冒烟测试（需要后端、Agent 服务与基础设施都在运行）
bash scripts/smoke-test.sh
```

冒烟测试覆盖产品需求文档第七章的验收项：注册登录、工作流创建与 DAG 校验、并行执行、
异步调度、消息重复投递、失败重试、Worker 故障恢复、Agent 调用与 Checkpoint、
人工审批、实时推送、技术报告与执行追踪。

---

## 接口

完整契约见 [docs/api-contract.md](docs/api-contract.md)，所有接口统一返回：

```json
{ "code": 0, "message": "success", "data": {}, "requestId": "req-001", "timestamp": "..." }
```

---

## 已知边界

第一版明确不实现：通用低代码平台、完整替代 n8n/Dify、通用 Agent 市场、
自研大模型或向量数据库、多 Agent 自由协商与无限递归委派。
