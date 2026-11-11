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
├── ops/                  可观测性与网关配置（Prometheus / Grafana / Nginx / OTel Collector）
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

### 8. 基于 OpenTelemetry 的跨服务链路追踪

一次工作流执行的所有步骤都属于**同一条 trace**：

- trace id 在创建执行时生成，存进 `workflow_execution.trace_id`；
- 每一跳消息都携带 trace id 与生产者的 span id，Java 与 Python 的 span 因此能挂成同一棵树；
- span 名遵循架构文档的 span 树：`workflow.scheduler`、`node.<节点名>`、`agent.graph`、
  `llm.call`、`tool.<工具名>`；
- span 同时落到 `trace_span` 表，采集器不可用时控制台依然能查到完整链路
  （`GET /api/v1/executions/{executionId}/trace`）。

Prometheus 指标覆盖架构文档要求的全部统计项：工作流/节点/Agent 耗时、
模型调用次数、Token 消耗、工具调用成功率、节点重试次数、工作流失败率。

### 9. Agent 侧的事务性发件箱与结果重查

Agent 服务的结果事件与执行记录在**同一个事务**里写入 PostgreSQL 的 `agent_outbox`，
再由后台任务投递到 RabbitMQ，因此结果事件不会丢失。

两套数据库之间没有跨库事务，所以恢复流程会**反查 Agent 的真实状态**：
Java 的恢复扫描器在重投之前调用 `GET /internal/invocations/{id}/result`，
拿到 Agent 的 checkpoint 与结果后再决定是采纳结果、延长租约还是重新执行。

### 10. 安全

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
| 统一入口（Nginx） | http://localhost |
| 控制台 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| API 文档 | http://localhost:8080/swagger-ui.html |
| RabbitMQ 管理台 | http://localhost:15672 (flowpilot / flowpilot) |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin / flowpilot) |
| OTLP 接入（gRPC / HTTP） | localhost:4317 / localhost:4318 |

首次启动时 Flyway 会自动创建表结构，并发布一个内置的 **GitHub 技术尽调** 工作流。

### 可观测性与网关

`docker-compose.yml` 除业务服务外，还包含 4 个基础设施容器，配置统一放在 `ops/` 目录下：

| 容器 | 端口 | 作用 | 配置文件 |
|---|---|---|---|
| nginx | 80 | 对外统一入口，反代控制台静态资源与 `/api` 后端接口，SSE 路径关闭缓冲 | `ops/nginx/nginx.conf` |
| prometheus | 9090 | 采集后端 `/actuator/prometheus` 与 otel-collector 的 `/metrics` | `ops/prometheus/prometheus.yml` |
| grafana | 3000 | 监控大盘，默认数据源指向 prometheus | `ops/grafana/` |
| otel-collector | 4317 / 4318 / 8889 | 接收 OTLP gRPC 与 HTTP，指标以 prometheus 格式暴露在 8889 | `ops/otel-collector/config.yml` |

- **Nginx**：对外只暴露 80 端口。`/api/` 反代到 `backend-api:8080`，
  SSE 流（`/api/v1/executions/{id}/events/stream`）使用 `proxy_buffering off` 与
  `proxy_read_timeout 3600s`，不会被缓冲；其余路径反代到 `frontend:80`。
- **Prometheus**：抓取间隔 15s，数据保存在 `prometheus-data` 卷，TSDB 目录为 `/prometheus`。
- **Grafana**：数据源与大盘通过 provisioning 自动加载，大盘为
  `ops/grafana/dashboards/flowpilot-overview.json`（默认只读挂载，需修改时改宿主目录即可）。
- **OTel Collector**：trace 走 `debug` 导出器，metrics 走 `prometheus` 导出器，
  被 Prometheus 以 `otel-collector:8889` 抓取。后端通过环境变量接入，例如：

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

Grafana 大盘使用下列指标名，指标由后端自行暴露：

| 指标 | 含义 |
|---|---|
| `flowpilot_workflow_duration_seconds` | 工作流执行耗时（Histogram） |
| `flowpilot_node_duration_seconds` | 节点执行耗时（Histogram） |
| `flowpilot_llm_calls_total` | LLM 调用次数（Counter） |
| `flowpilot_tool_calls_total` | 工具调用次数（Counter，带 `outcome` 标签） |

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
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | Grafana 管理员账号密码 | admin / flowpilot |

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
