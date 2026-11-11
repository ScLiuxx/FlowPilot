# FlowPilot 核心执行引擎设计 V1.0

**项目名称：** FlowPilot  
**文档版本：** V1.0  
**模块名称：** Workflow Execution Engine  
**核心技术：** Java 21、Spring Boot 3、MySQL 8、RabbitMQ、Redis  
**设计目标：** 构建可靠、可恢复、支持并行调度的 DAG 工作流执行引擎。

---

# 1. 核心执行引擎概述

## 1.1 为什么需要独立的执行引擎？

FlowPilot 并不是一个简单的 Agent 聊天应用。

用户提交一个 GitHub 仓库后，系统需要执行一系列任务：

1. 获取仓库基本信息。
2. 获取 Commit 历史。
3. 获取 Issues 和 Pull Requests。
4. 获取 Releases 和依赖信息。
5. 对采集的数据进行清洗和统计。
6. 调用 Agent 分析仓库架构和维护情况。
7. 等待人工审核。
8. 根据审核结果继续执行。
9. 生成最终技术尽调报告。

这些任务之间存在明确的依赖关系。

例如，Commit、Issue 和 Release 数据可以并行采集，但 Agent 必须等待所有必要的数据采集任务完成。

如果简单地使用一个线程从头执行到尾，会产生以下问题：

- 无法充分利用并行能力。
- 一个节点失败可能导致整个任务无法继续。
- 无法独立重试某个节点。
- 服务重启后难以恢复执行。
- 无法清晰展示每个节点的运行状态。
- Agent 长时间执行会占用业务线程。

因此，需要一个独立的 Workflow Execution Engine。

它负责将工作流定义转化为可执行任务，并协调整个任务生命周期。

## 1.2 核心设计思想

FlowPilot 执行引擎遵循以下原则：

**第一，DAG 决定执行顺序。**

节点之间通过依赖关系构成有向无环图。只有依赖满足的节点才能执行。

**第二，状态机决定节点能否执行。**

节点不是简单地从未执行变成已执行，而是需要经过 READY、RUNNING、RETRY_WAIT 等状态。

**第三，消息队列负责异步分发。**

调度器不直接执行耗时任务，而是将任务投递给 Worker。

**第四，数据库负责持久化执行事实。**

任务状态、执行记录、重试次数和事件都必须持久化。

**第五，恢复机制负责处理异常。**

服务重启、消息重复投递、Worker 崩溃，都不能导致整个工作流状态丢失。

整体架构：

```text
                  用户请求
                     |
                     v
              Spring Boot API
                     |
                     v
              Workflow Service
                     |
                     v
             Workflow Scheduler
                     |
              +------+------+
              |             |
              v             v
         MySQL事务       RabbitMQ
              |             |
              |             v
              |       Worker Cluster
              |             |
              |      +------+------+
              |      |      |      |
              |      v      v      v
              |     HTTP   CODE   AGENT
              |                    |
              |                    v
              |               Agent Service
              |                    |
              |                    v
              |                LangGraph
              |
              v
        Execution State
```

这里最重要的职责边界是：

- Scheduler 决定执行什么。
- RabbitMQ 负责把任务交给 Worker。
- Worker 负责执行具体节点。
- MySQL 记录执行状态。
- Agent Service 负责 Agent 内部执行。

# 2. DAG 工作流模型

## 2.1 DAG 定义

DAG 是 Directed Acyclic Graph，即有向无环图。

可以将一个工作流表示为：

\[
G=(V,E)
\]

其中：

- \(V\) 表示节点集合。
- \(E\) 表示节点之间的有向边集合。

例如：

```text
        A
       / \
      B   C
       \ /
        D
        |
        E
```

对应关系：

\[
V=\{A,B,C,D,E\}
\]

\[
E=\{(A,B),(A,C),(B,D),(C,D),(D,E)\}
\]

节点 A 完成后，B 和 C 可以并行执行。

节点 D 必须等待 B、C 全部成功。

节点 E 必须等待 D 成功。

## 2.2 节点类型

第一阶段支持以下节点类型：

| 节点类型 | 标识 | 作用 |
|---|---|---|
| HTTP | HTTP | 调用外部 HTTP API |
| CODE | CODE | 执行确定性数据处理逻辑 |
| CONDITION | CONDITION | 根据条件选择执行分支 |
| LLM | LLM | 执行简单模型调用 |
| AGENT | AGENT | 调用 LangGraph Agent |
| HUMAN | HUMAN | 等待人工审批 |
| MERGE | MERGE | 汇聚多个分支的执行结果 |

其中，HTTP、CODE、CONDITION、HUMAN、MERGE 主要由 Java 执行引擎管理。

LLM 和 AGENT 可以通过 Python Agent Service 处理，也可以根据具体实现由 Java 调用独立模型服务。

不建议第一版同时实现大量不同的节点类型。优先打通 HTTP、CODE、AGENT、HUMAN 四种类型。

## 2.3 节点定义模型

建议使用 Java 类表示工作流节点。

```java
public class WorkflowNode {

    private String id;

    private NodeType type;

    private String name;

    private List<String> dependencies;

    private Map<String, Object> config;

    private RetryPolicy retryPolicy;

    private TimeoutPolicy timeoutPolicy;
}
```

节点类型：

```java
public enum NodeType {

    HTTP,

    CODE,

    CONDITION,

    LLM,

    AGENT,

    HUMAN,

    MERGE
}
```

依赖关系通过 dependencies 表示。

例如：

```json
{
  "id": "analyze_repository",
  "type": "AGENT",
  "name": "仓库综合分析",
  "dependencies": [
    "collect_commits",
    "collect_issues",
    "collect_releases"
  ],
  "config": {
    "agentType": "REPOSITORY_ANALYST"
  }
}
```

该节点只有在三个依赖节点全部满足成功条件后才能执行。

# 3. 工作流校验与拓扑排序

工作流正式执行之前，必须先完成 DAG 校验。

不能直接信任用户提交的 JSON。

## 3.1 基础校验

校验内容包括：

1. 节点 ID 是否唯一。
2. 节点类型是否合法。
3. 节点依赖是否存在。
4. 是否存在自依赖。
5. 是否存在循环依赖。
6. 节点配置是否符合对应类型的 Schema。
7. 节点输入输出映射是否合法。
8. 是否存在不可达节点或无效分支。

例如：

```text
A -> B -> C -> A
```

这是一个有环图，不能执行。

如果不进行环检测，调度器可能一直等待某个永远无法满足的依赖。

## 3.2 Kahn 拓扑排序

采用 Kahn 算法进行拓扑排序。

核心思想：

1. 计算每个节点的入度。
2. 将所有入度为 0 的节点放入队列。
3. 不断取出节点。
4. 将该节点加入拓扑序列。
5. 删除该节点的出边，并减少后继节点入度。
6. 如果某个节点入度变成 0，将其加入队列。
7. 最终检查拓扑序列长度是否等于节点总数。

Java 实现：

```java
public List<String> topologicalSort(
        Map<String, WorkflowNode> nodes) {

    Map<String, Integer> indegree = new HashMap<>();

    Map<String, List<String>> adjacency = new HashMap<>();

    for (String nodeId : nodes.keySet()) {
        indegree.put(nodeId, 0);
        adjacency.put(nodeId, new ArrayList<>());
    }

    for (WorkflowNode node : nodes.values()) {

        for (String dependency : node.getDependencies()) {

            if (!nodes.containsKey(dependency)) {
                throw new IllegalArgumentException(
                    "Dependency not found: " + dependency
                );
            }

            if (dependency.equals(node.getId())) {
                throw new IllegalArgumentException(
                    "Self dependency detected"
                );
            }

            adjacency.get(dependency).add(node.getId());

            indegree.put(
                node.getId(),
                indegree.get(node.getId()) + 1
            );
        }
    }

    Queue<String> queue = new ArrayDeque<>();

    for (Map.Entry<String, Integer> entry : indegree.entrySet()) {

        if (entry.getValue() == 0) {
            queue.offer(entry.getKey());
        }
    }

    List<String> result = new ArrayList<>();

    while (!queue.isEmpty()) {

        String current = queue.poll();

        result.add(current);

        for (String next : adjacency.get(current)) {

            int degree = indegree.get(next) - 1;

            indegree.put(next, degree);

            if (degree == 0) {
                queue.offer(next);
            }
        }
    }

    if (result.size() != nodes.size()) {
        throw new IllegalArgumentException(
            "Cycle detected in workflow"
        );
    }

    return result;
}
```

时间复杂度：

\[
O(|V|+|E|)
\]

空间复杂度：

\[
O(|V|+|E|)
\]

对于普通业务工作流，这个复杂度足够使用。

## 3.3 为什么不能只保存拓扑排序结果？

拓扑排序只能提供一种合法的执行顺序。

例如：

```text
A -> B
A -> C
B -> D
C -> D
```

拓扑排序可能返回：

```text
A, B, C, D
```

但这并不意味着 B 必须在 C 之前执行。

实际上：

```text
A 完成
  |
  +---- B
  |
  +---- C
```

B 和 C 可以同时执行。

因此，调度器必须保留完整的 DAG 依赖关系，而不能简单地把拓扑排序结果当成一个串行任务列表。

# 4. 节点状态机设计

## 4.1 节点状态

节点状态是执行引擎最重要的控制变量。

建议定义：

```java
public enum NodeStatus {

    PENDING,

    READY,

    RUNNING,

    RETRY_WAIT,

    WAITING_HUMAN,

    SUCCEEDED,

    FAILED,

    SKIPPED,

    CANCELED
}
```

状态说明：

| 状态 | 说明 |
|---|---|
| PENDING | 尚未满足执行条件 |
| READY | 依赖已满足，等待执行 |
| RUNNING | 正在执行 |
| RETRY_WAIT | 失败后等待重试 |
| WAITING_HUMAN | 等待人工审批 |
| SUCCEEDED | 执行成功 |
| FAILED | 执行失败 |
| SKIPPED | 因分支条件等原因跳过 |
| CANCELED | 已取消 |

## 4.2 状态转换

主要状态转换：

```text
              PENDING
                 |
                 v
               READY
                 |
                 v
              RUNNING
              /  |  \
             /   |   \
            v    v    v
       SUCCEEDED FAILED WAITING_HUMAN
                   |         |
                   v         v
               RETRY_WAIT  READY
                   |
                   v
                READY
```

失败后：

- 允许重试：FAILED → RETRY_WAIT → READY。
- 不允许重试：RUNNING → FAILED。

人工审批：

- Agent 执行中触发中断。
- 节点进入 WAITING_HUMAN。
- 审批通过后重新进入 READY 或进入专门的恢复调度状态。
- Agent Service 使用原 thread_id 恢复执行。

这里需要强调：恢复 Agent 时，不应把 Agent 当成一个全新的调用。

## 4.3 合法状态转换校验

不能让任何业务代码随意修改状态。

建议建立统一状态转换服务。

```java
public class NodeStateMachine {

    private static final Map<NodeStatus, Set<NodeStatus>>
        TRANSITIONS = Map.of(

        NodeStatus.PENDING,
        Set.of(NodeStatus.READY, NodeStatus.SKIPPED,
               NodeStatus.CANCELED),

        NodeStatus.READY,
        Set.of(NodeStatus.RUNNING, NodeStatus.CANCELED),

        NodeStatus.RUNNING,
        Set.of(NodeStatus.SUCCEEDED, NodeStatus.FAILED,
               NodeStatus.RETRY_WAIT,
               NodeStatus.WAITING_HUMAN,
               NodeStatus.CANCELED),

        NodeStatus.RETRY_WAIT,
        Set.of(NodeStatus.READY, NodeStatus.CANCELED),

        NodeStatus.WAITING_HUMAN,
        Set.of(NodeStatus.READY, NodeStatus.CANCELED)
    );

    public static boolean canTransition(
            NodeStatus from,
            NodeStatus to) {

        return TRANSITIONS
            .getOrDefault(from, Set.of())
            .contains(to);
    }
}
```

实际项目中，应进一步考虑：

- 终态是否允许重新执行。
- 人工拒绝如何处理。
- 条件分支跳过后如何传播状态。
- 整个工作流取消时如何处理正在执行的节点。

状态机不只是一个枚举类，而是整个执行引擎的业务规则中心。

# 5. DAG 调度算法

## 5.1 调度核心问题

当一个节点执行完成后，调度器需要判断哪些后继节点可以执行。

假设：

```text
       A
      / \
     B   C
      \ /
       D
```

A 完成后，B 和 C 都可以进入 READY。

B 完成后，D 不能立即执行，因为 C 还没有完成。

只有 B 和 C 全部成功，D 才能进入 READY。

因此，调度器的核心判断是：

\[
Ready(v)=
\begin{cases}
1,& \forall u\in Pred(v),\ Status(u)=SUCCEEDED\\
0,& \text{otherwise}
\end{cases}
\]

其中：

- \(Pred(v)\) 表示节点 v 的直接前驱节点集合。
- Status(u) 表示前驱节点的状态。

## 5.2 基础调度流程

```text
节点执行成功
      |
      v
发布 NodeCompleted 消息
      |
      v
Scheduler 消费消息
      |
      v
获取当前节点的后继节点
      |
      v
检查每个后继节点的全部依赖
      |
      +---- 依赖未全部完成
      |          |
      |          v
      |        保持 PENDING
      |
      +---- 依赖全部成功
                 |
                 v
             更新 READY
                 |
                 v
           写入 Outbox
                 |
                 v
          RabbitMQ 投递任务
```

## 5.3 依赖判定实现

```java
public boolean canExecute(
        String nodeId,
        Map<String, WorkflowNode> nodes,
        Map<String, NodeStatus> statuses) {

    WorkflowNode node = nodes.get(nodeId);

    for (String dependency : node.getDependencies()) {

        NodeStatus status = statuses.get(dependency);

        if (status != NodeStatus.SUCCEEDED) {
            return false;
        }
    }

    return true;
}
```

这是基础实现。

真正的生产实现不能每次都把整张 DAG 的状态加载到内存中。

应该根据当前节点查询其直接后继节点及其依赖状态。

对于规模较大的工作流，可以在工作流执行初始化时生成依赖关系索引，或者将节点依赖关系单独持久化。

第一阶段可以从 workflow_version.definition_json 读取依赖配置，并在启动执行时将节点关系加载到调度上下文。

## 5.4 并发调度中的竞争问题

考虑：

```text
       A
      / \
     B   C
      \ /
       D
```

假设 B 和 C 几乎同时完成。

可能发生：

```text
Scheduler 1：处理 B 完成
Scheduler 2：处理 C 完成
```

两个 Scheduler 都试图判断 D 是否可以执行。

如果没有并发控制，可能发生：

1. Scheduler 1 发现 C 尚未完成。
2. Scheduler 2 发现 B 尚未完成。
3. 两者都不调度 D。

或者在不恰当的更新顺序下，两者都向 RabbitMQ 发送 D 的执行消息。

因此，需要在数据库层实现原子状态转换。

建议：

```sql
UPDATE node_execution
SET status = 'READY',
    version = version + 1
WHERE id = ?
  AND status = 'PENDING';
```

只有一个事务能够成功把 PENDING 更新为 READY。

但这还不够。

完整流程应当是：

1. 查询后继节点。
2. 检查全部前驱节点是否成功。
3. 使用条件更新将节点从 PENDING 改为 READY。
4. 如果更新成功，在同一事务中插入 NodeExecute Outbox。
5. 提交事务。

这样即使两个 Scheduler 同时判断同一个节点，也只有一个能够成功完成状态转换并生成执行消息。

注意：依赖状态检查与 READY 状态更新之间必须有适当的并发保护。

第一阶段可以通过锁定对应的工作流执行记录来串行化同一工作流的调度决策。后续如果需要提高并发度，再优化为按节点依赖关系进行更细粒度的锁控制。

# 6. Worker 执行模型

## 6.1 为什么使用独立 Worker？

不要在 Controller 或 Scheduler 中直接执行节点逻辑。

例如：

```java
@PostMapping("/execute")
public Result execute() {

    // 不推荐：直接执行耗时任务
    githubService.collectRepository();

    return Result.success();
}
```

这种实现会导致：

- HTTP 请求长期占用线程。
- 服务重启后任务状态难以恢复。
- 无法灵活扩容。
- 无法隔离不同类型任务。
- 难以实现任务超时和重试。

应该使用独立 Worker。

```text
Spring Boot API
      |
      v
创建工作流执行实例
      |
      v
RabbitMQ
      |
      v
Worker
      |
      v
执行节点
```

API 只负责创建任务并返回执行编号。

## 6.2 Worker 接口设计

定义统一节点执行接口：

```java
public interface NodeExecutor {

    NodeType supportType();

    NodeResult execute(NodeContext context);
}
```

不同节点类型分别实现：

```java
@Component
public class HttpNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.HTTP;
    }

    @Override
    public NodeResult execute(NodeContext context) {

        // 执行 HTTP 请求

        return NodeResult.success();
    }
}
```

```java
@Component
public class CodeNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.CODE;
    }

    @Override
    public NodeResult execute(NodeContext context) {

        // 执行确定性数据处理逻辑

        return NodeResult.success();
    }
}
```

```java
@Component
public class AgentNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.AGENT;
    }

    @Override
    public NodeResult execute(NodeContext context) {

        // 通过消息或 Agent Service 调用 Python Agent

        return NodeResult.success();
    }
}
```

执行器注册表：

```java
@Component
public class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> executors;

    public NodeExecutorRegistry(
            List<NodeExecutor> executorList) {

        this.executors = executorList.stream()
            .collect(Collectors.toMap(
                NodeExecutor::supportType,
                Function.identity()
            ));
    }

    public NodeExecutor get(NodeType type) {

        NodeExecutor executor = executors.get(type);

        if (executor == null) {
            throw new IllegalArgumentException(
                "Unsupported node type: " + type
            );
        }

        return executor;
    }
}
```

这是一种策略模式。

相比在一个巨大的 switch-case 中编写所有节点逻辑，这种方式更容易扩展和测试。

## 6.3 NodeContext

节点执行时需要获取上下文。

```java
public class NodeContext {

    private Long workflowExecutionId;

    private Long nodeExecutionId;

    private Integer attemptNo;

    private String nodeKey;

    private Map<String, Object> inputs;

    private Map<String, Object> upstreamOutputs;

    private String traceId;

    private Long leaseToken;
}
```

其中：

- workflowExecutionId：工作流执行实例。
- nodeExecutionId：节点执行实例。
- attemptNo：执行尝试次数。
- inputs：节点输入。
- upstreamOutputs：上游节点输出。
- traceId：分布式追踪标识。
- leaseToken：当前执行租约标识。

上下游数据传递不应该通过 Java 静态变量或者全局 Map 实现。

必须通过持久化结果或可恢复的数据引用传递。

对于大型数据，不建议将完整内容直接塞入 RabbitMQ 消息。

可以传递：

```json
{
  "datasetId": "dataset_10001",
  "storageKey": "flowpilot/executions/10001/commits.json"
}
```

Worker 根据引用获取实际数据。

# 7. 并行执行与线程池管理

## 7.1 并行调度

考虑以下工作流：

```text
                 A
                 |
          +------+------+
          |      |      |
          v      v      v
          B      C      D
          |      |      |
          +------+------+
                 |
                 v
                 E
```

如果 B、C、D 互相没有依赖关系，则可以并行执行。

理论上：

\[
T_{parallel}\approx \max(T_B,T_C,T_D)
\]

而串行执行需要：

\[
T_{serial}=T_B+T_C+T_D
\]

因此，合理并行化能够显著降低工作流总耗时。

## 7.2 并发限制

并行执行不代表无限制并发。

需要设置：

- 全局 Worker 并发上限。
- 单个工作流最大并发节点数。
- 不同节点类型的并发限制。
- 外部 API 调用限流。
- Agent 调用并发限制。

例如：

```yaml
flowpilot:
  worker:
    max-concurrency: 16

  workflow:
    max-parallel-nodes: 8

  executor:
    http:
      max-concurrency: 10

    agent:
      max-concurrency: 4
```

这些值仅作为开发初期的示例配置，实际需要通过压测确定。

对于 GitHub API，还需要根据 API Rate Limit 控制调用速率。

## 7.3 线程池隔离

不同类型的任务具有不同的资源特征。

HTTP 节点主要受网络 I/O 影响。

CODE 节点可能消耗较多 CPU。

Agent 节点可能等待较长时间的模型响应。

不建议所有任务共享一个无限增长的线程池。

可以采用：

```text
Worker
  |
  +---- HTTP Executor Pool
  |
  +---- CODE Executor Pool
  |
  +---- AGENT Executor Pool
```

通过独立线程池和信号量进行资源隔离。

但是要注意：AGENT 节点如果采用 RabbitMQ 异步调用 Python 服务，就不应该让 Java 线程一直阻塞等待模型完成。

更合理的方式是：

1. Java 发送 AgentExecute。
2. Agent Service 接收任务。
3. Java 将节点状态更新为等待 Agent 结果的状态。
4. Agent Service 异步执行。
5. 完成后发送 AgentCompleted。
6. Java 更新节点状态并继续调度。

因此，Agent 节点的执行生命周期需要和普通同步节点有所区别。

# 8. 失败处理与重试机制

## 8.1 失败分类

不同错误不能使用完全相同的重试策略。

建议将异常划分为：

```java
public enum ErrorCategory {

    TRANSIENT,

    RATE_LIMIT,

    VALIDATION,

    PERMISSION,

    BUSINESS,

    INTERNAL
}
```

解释：

| 类型 | 典型情况 | 默认处理 |
|---|---|---|
| TRANSIENT | 网络超时 | 重试 |
| RATE_LIMIT | API 限流 | 延迟重试 |
| VALIDATION | 参数错误 | 不重试 |
| PERMISSION | 权限不足 | 不重试 |
| BUSINESS | 业务规则失败 | 根据业务决定 |
| INTERNAL | 内部异常 | 有限重试并告警 |

## 8.2 指数退避

建议采用指数退避：

\[
T_n=\min(T_{max},T_0\cdot 2^{n-1})
\]

例如：

```text
第一次失败：等待 1 秒
第二次失败：等待 2 秒
第三次失败：等待 4 秒
第四次失败：等待 8 秒
```

可以进一步增加随机抖动：

\[
T_n'=T_n\cdot U(0.8,1.2)
\]

避免大量任务在同一时刻重新发起请求。

## 8.3 重试调度

不要让 Worker 使用 Thread.sleep() 等待重试时间。

错误实现：

```java
Thread.sleep(10000);
retryTask();
```

这种做法会长期占用线程。

应该采用持久化延迟调度：

```text
节点执行失败
      |
      v
更新 RETRY_WAIT
      |
      v
计算 next_retry_at
      |
      v
保存数据库
      |
      v
Retry Scheduler 定期扫描
      |
      v
到期后更新 READY
      |
      v
写入 Outbox
      |
      v
RabbitMQ 重新投递
```

这样 Worker 可以立即处理其他任务。

第一阶段可以使用数据库定时扫描实现，不必立即引入复杂的延迟消息插件。

# 9. Agent 节点的特殊执行机制

Agent 与普通节点有本质区别。

普通节点通常是：

```text
输入 -> 执行 -> 输出
```

Agent 节点可能是：

```text
输入
 |
 v
LLM 推理
 |
 v
工具调用
 |
 v
观察结果
 |
 v
继续推理
 |
 v
多轮执行
 |
 v
最终输出
```

而且 Agent 可能运行数分钟，甚至因为人工审批而暂停数小时。

因此，必须采用异步执行。

## 9.1 Agent 调用流程

```text
Java Scheduler
      |
      v
创建 agent_invocation
      |
      v
写入 AgentExecute Outbox
      |
      v
RabbitMQ
      |
      v
Python Agent Service
      |
      v
LangGraph Runtime
      |
      +---- LLM
      |
      +---- Tool Calling
      |
      +---- Checkpoint
      |
      v
AgentCompleted
      |
      v
Java 更新节点状态
      |
      v
继续 DAG 调度
```

## 9.2 Agent 中断

当 Agent 需要人工审核时：

```text
Agent Runtime
      |
      v
LangGraph interrupt()
      |
      v
保存 Checkpoint
      |
      v
AgentInterrupted
      |
      v
Java 更新节点状态
      |
      v
WAITING_HUMAN
      |
      v
用户审批
      |
      v
AgentResume
      |
      v
LangGraph Command(resume=...)
      |
      v
继续执行
```

需要特别注意：

LangGraph 的 Interrupt 恢复会重新进入触发中断的节点函数，因此中断之前的副作用必须具备幂等性。

例如，不能在 interrupt 前重复发送邮件或重复创建外部资源。

## 9.3 Agent 超时

Agent 节点需要配置超时策略。

例如：

```json
{
  "agentType": "REPOSITORY_ANALYST",
  "timeoutSeconds": 300,
  "maxTokens": 12000,
  "maxToolCalls": 20,
  "maxRetries": 2
}
```

需要区分：

- 单次模型请求超时。
- 单次工具调用超时。
- 整个 Agent Invocation 超时。
- 人工审批等待超时。

不能因为用户审批等待了两个小时，就把整个 Agent 误判为执行超时。

人工等待时间应该独立计算。

# 10. 故障恢复机制

## 10.1 为什么需要 Recovery Scheduler？

假设：

```text
Worker A 正在执行节点 B
```

此时 Worker A 崩溃。

RabbitMQ 可能重新投递未确认的消息，但不能仅依赖 RabbitMQ 解决所有故障。

因为 Worker 可能已经：

- 成功执行了外部操作。
- 更新了部分数据库状态。
- 写入了 Agent Checkpoint。
- 但没有完成最终状态提交。

因此，需要独立的恢复扫描器。

## 10.2 Lease 机制

节点执行时设置：

```text
worker_id = worker-01
lease_token = 100
lease_expire_at = 2026-11-11 18:10:00
heartbeat_at = 2026-11-11 18:09:30
```

Worker 定期续租：

```sql
UPDATE node_execution
SET heartbeat_at = NOW(3),
    lease_expire_at = ?
WHERE id = ?
  AND status = 'RUNNING'
  AND worker_id = ?
  AND lease_token = ?;
```

如果更新行数为 0，说明当前 Worker 已失去执行权。

它必须停止后续业务操作，并且不能再提交执行结果。

## 10.3 恢复扫描

Recovery Scheduler 定期查询：

```sql
SELECT id
FROM node_execution
WHERE status = 'RUNNING'
  AND lease_expire_at < NOW(3)
ORDER BY lease_expire_at
LIMIT 100;
```

随后逐条或分批处理。

实际生产实现应使用短事务和 `FOR UPDATE SKIP LOCKED`，避免多个恢复实例同时领取同一批节点。

恢复流程：

```text
扫描租约过期节点
        |
        v
获取数据库锁
        |
        v
确认节点仍为 RUNNING
        |
        v
增加 lease_token
        |
        v
检查当前 Attempt
        |
        v
判断恢复策略
        |
        +---- 普通节点
        |       |
        |       v
        |    创建重试 Attempt
        |
        +---- Agent 节点
        |       |
        |       v
        |    查询 LangGraph Checkpoint
        |       |
        |       v
        |    决定恢复或重新执行
        |
        +---- 已完成节点
                |
                v
             修复业务状态
```

## 10.4 Fencing Token

仅仅设置 lease_expire_at 仍然不够。

假设：

1. Worker A 获取任务。
2. Worker A 执行时间过长，租约过期。
3. Worker B 获取同一个任务。
4. Worker A 恢复运行。

如果没有额外保护，A 和 B 可能同时提交结果。

因此引入 Fencing Token。

每次重新获得执行权时，递增 lease_token。

```text
Worker A:
lease_token = 100

Worker B:
lease_token = 101
```

所有执行结果提交必须携带 token。

数据库只接受当前有效 token。

这能够防止旧 Worker 在租约过期后覆盖新 Worker 的结果。

但是要注意：

Fencing Token 只能保护支持该校验的资源。

如果 Worker A 在失去租约后仍然向第三方 API 发起请求，MySQL 无法撤销已经发生的外部副作用。

因此，对于外部写操作，仍然需要业务幂等键、调用前检查以及必要的补偿机制。

# 11. 工作流失败传播

节点失败后，不能简单地把整个工作流立即设置为 FAILED。

必须考虑 DAG 中其他节点的状态。

例如：

```text
       A
      / \
     B   C
      \ /
       D
```

如果 B 执行失败：

- C 可能仍然正常执行。
- D 无法满足全部依赖。
- 如果 B 允许重试，则 D 应继续等待。
- 如果 B 最终失败，则根据失败策略决定是否取消 D。

建议第一阶段使用以下策略：

| 节点类型 | 失败行为 |
|---|---|
| 普通 HTTP 节点 | 根据重试策略重试 |
| CODE 节点 | 默认失败，允许配置重试 |
| AGENT 节点 | 有限重试，超限后失败 |
| HUMAN 节点 | 根据审批结果决定继续或终止 |
| CONDITION 节点 | 根据分支结果跳过不满足条件的节点 |

工作流失败条件：

1. 存在不可恢复的失败节点。
2. 该节点影响必要的下游路径。
3. 所有仍在执行的节点已经完成、取消或被安全终止。

工作流不能在仍有活动节点时过早进入 FAILED。

第一阶段建议采用 Fail Fast + Cancel Remaining 的简化策略：

- 关键节点最终失败后，工作流进入终止处理阶段。
- 尚未开始的节点标记为 CANCELED 或 SKIPPED。
- 正在执行的节点发送取消请求。
- 等待可确认的执行结果或超时回收。
- 最终更新工作流状态为 FAILED。

如果后续需要支持复杂的错误分支，可以增加节点级失败出口和错误处理节点。

# 12. 工作流取消机制

用户可能在工作流执行过程中主动取消任务。

需要区分：

- 尚未执行的节点。
- 正在执行的节点。
- 等待重试的节点。
- 等待人工审批的节点。
- 正在执行的 Agent。

取消流程：

```text
用户请求取消
      |
      v
Workflow Service
      |
      v
更新工作流取消标志
      |
      v
写入 WorkflowCanceled Outbox
      |
      v
通知相关 Worker
      |
      +---- READY 节点：直接取消
      |
      +---- RETRY_WAIT 节点：取消
      |
      +---- RUNNING 节点：请求协作式取消
      |
      +---- WAITING_HUMAN 节点：取消审批等待
      |
      +---- AGENT 节点：发送 AgentCancel
```

取消不能保证所有外部操作立即停止。

例如，已经发送给第三方 API 的请求可能无法撤销。

因此，系统应该明确区分：

- CANCEL_REQUESTED：取消请求已发出。
- CANCELED：系统确认任务已经停止。

如果需要更精确的状态管理，可以将工作流取消标志和最终执行状态分开保存。

# 13. 执行引擎核心模块划分

建议 Java Backend 按以下模块组织：

```text
com.flowpilot
│
├── workflow
│   ├── controller
│   ├── service
│   ├── model
│   └── repository
│
├── engine
│   ├── definition
│   │   ├── WorkflowParser
│   │   ├── WorkflowValidator
│   │   └── TopologicalSorter
│   │
│   ├── scheduler
│   │   ├── WorkflowScheduler
│   │   ├── NodeScheduler
│   │   └── DependencyResolver
│   │
│   ├── executor
│   │   ├── NodeExecutor
│   │   ├── HttpNodeExecutor
│   │   ├── CodeNodeExecutor
│   │   └── AgentNodeExecutor
│   │
│   ├── state
│   │   ├── NodeStateMachine
│   │   └── WorkflowStateMachine
│   │
│   ├── recovery
│   │   ├── RecoveryScheduler
│   │   ├── LeaseManager
│   │   └── RecoveryHandler
│   │
│   └── retry
│       ├── RetryPolicy
│       └── RetryScheduler
│
├── messaging
│   ├── producer
│   ├── consumer
│   ├── outbox
│   └── idempotency
│
├── agent
│   ├── AgentClient
│   ├── AgentEventHandler
│   └── AgentInvocationService
│
├── approval
│
├── report
│
└── common
    ├── exception
    ├── result
    ├── trace
    └── config
```

模块划分的重点不是包名，而是职责边界。

例如：

- Scheduler 不负责执行 HTTP 请求。
- Executor 不负责决定整个 DAG 的执行顺序。
- Recovery 不负责重新定义工作流。
- Messaging 不应该包含复杂业务规则。
- AgentClient 不应该直接修改节点执行状态。

# 14. 核心执行流程伪代码

## 14.1 创建工作流执行

```java
@Transactional
public Long startWorkflow(
        Long workflowVersionId,
        Map<String, Object> input) {

    // 1. 查询已发布版本
    WorkflowVersion workflow =
        workflowVersionService.getPublished(workflowVersionId);

    // 2. 解析工作流
    WorkflowDefinition definition =
        parser.parse(workflow.getDefinitionJson());

    // 3. 校验 DAG
    validator.validate(definition);

    // 4. 创建工作流执行实例
    WorkflowExecution execution =
        executionService.create(workflow, input);

    // 5. 初始化所有节点
    nodeService.initializeNodes(execution, definition);

    // 6. 初始化事件
    eventService.append(
        execution.getId(),
        "WORKFLOW_CREATED"
    );

    // 7. 写入启动事件
    outboxService.publishWorkflowStarted(execution);

    return execution.getId();
}
```

注意：实际实现还应保证重复请求不会意外创建多个工作流执行实例。可以为启动请求设计幂等键，并建立对应唯一约束。

## 14.2 调度节点

```java
@Transactional
public void scheduleNode(Long nodeExecutionId) {

    NodeExecution node =
        nodeRepository.findByIdForUpdate(nodeExecutionId);

    if (node.getStatus() != NodeStatus.PENDING) {
        return;
    }

    boolean ready =
        dependencyResolver.canExecute(nodeExecutionId);

    if (!ready) {
        return;
    }

    int updated = nodeRepository.markReady(
        nodeExecutionId
    );

    if (updated == 0) {
        return;
    }

    outboxService.publishNodeExecute(node);
}
```

这里的 findByIdForUpdate 需要通过数据库行锁实现。

依赖判定必须在适当的事务保护范围内完成，不能只在 Java 内存中判断后就认为调度成功。

## 14.3 Worker 执行节点

```java
public void executeNode(NodeExecuteMessage message) {

    // 1. 校验消息
    validateMessage(message);

    // 2. 幂等检查
    if (alreadyCompleted(message)) {
        return;
    }

    // 3. 领取执行权
    Lease lease = leaseManager.acquire(message);

    if (lease == null) {
        return;
    }

    // 4. 创建执行上下文
    NodeContext context =
        contextFactory.create(message, lease);

    // 5. 根据类型选择 Executor
    NodeExecutor executor =
        registry.get(message.getNodeType());

    try {

        // 6. 执行节点
        NodeResult result = executor.execute(context);

        // 7. 提交执行结果
        resultService.complete(
            message,
            lease,
            result
        );

    } catch (Exception e) {

        // 8. 统一异常处理
        failureService.handle(
            message,
            lease,
            e
        );
    }
}
```

这只是执行引擎的结构示意。

真正实现时，消息 ACK、业务事务、执行权校验和异常分类需要形成完整的处理流程。

## 14.4 节点完成后的调度

```java
@Transactional
public void handleNodeCompleted(
        Long nodeExecutionId) {

    NodeExecution current =
        nodeRepository.findByIdForUpdate(nodeExecutionId);

    if (current.getStatus() != NodeStatus.SUCCEEDED) {
        return;
    }

    // 获取直接后继节点
    List<NodeExecution> successors =
        nodeRepository.findSuccessors(nodeExecutionId);

    for (NodeExecution successor : successors) {

        if (successor.getStatus() != NodeStatus.PENDING) {
            continue;
        }

        boolean ready =
            dependencyResolver.canExecute(
                successor.getId()
            );

        if (ready) {

            int updated =
                nodeRepository.markReady(
                    successor.getId()
                );

            if (updated == 1) {
                outboxService.publishNodeExecute(
                    successor
                );
            }
        }
    }

    // 检查整个工作流是否结束
    workflowCompletionService.checkCompletion(
        current.getWorkflowExecutionId()
    );
}
```

生产实现需要保证同一工作流内并发调度的正确性，并处理条件分支、跳过节点和失败传播。

# 15. 执行引擎测试方案

执行引擎不能只测试正常流程。

真正有价值的是异常场景测试。

## 15.1 DAG 正确性测试

测试内容：

- 正常 DAG。
- 循环依赖。
- 自依赖。
- 不存在的依赖。
- 多个起始节点。
- 多个终止节点。
- 大规模 DAG。

## 15.2 并发调度测试

构造：

```text
       A
      / \
     B   C
      \ /
       D
```

测试：

1. A 完成后 B、C 均进入 READY。
2. B、C 能够并行执行。
3. B 完成后 D 仍保持 PENDING。
4. C 完成后 D 进入 READY。
5. 多个 Scheduler 同时处理完成事件时，D 只生成一个有效执行任务。

## 15.3 幂等测试

模拟：

- RabbitMQ 重复投递同一消息。
- Worker 执行成功但 ACK 前崩溃。
- Publisher 发送成功但更新 Outbox 前崩溃。
- AgentCompleted 重复发送。
- 用户重复点击审批按钮。

验证：

业务执行效果不会因为消息重复而重复产生。

## 15.4 故障恢复测试

模拟：

- Worker 执行过程中被强制终止。
- Worker 心跳中断。
- Worker 租约过期后恢复。
- 两个 Worker 同时竞争一个节点。
- Agent 执行期间 Java Backend 重启。
- Agent 写入 Checkpoint 后服务崩溃。
- 人工审批期间服务重启。

## 15.5 性能测试

建议关注：

| 指标 | 测量目标 |
|---|---|
| DAG 校验耗时 | 不同节点数量下的耗时 |
| 节点调度延迟 | READY 到消息发送的时间 |
| 消息消费延迟 | 消息进入队列到 Worker 开始执行的时间 |
| 并行执行效率 | 相比串行执行的耗时改善 |
| 恢复延迟 | 租约过期到任务重新调度的时间 |
| 幂等冲突率 | 重复消息导致的重复处理情况 |
| 数据库压力 | 调度期间的查询与更新次数 |

不要提前承诺一个未经测试的性能数字。

先建立基准测试，再根据实际数据进行优化。

# 16. 第一阶段实施路线

建议按以下顺序实现。

## 阶段一：DAG 核心算法

实现：

- WorkflowNode。
- WorkflowDefinition。
- WorkflowParser。
- WorkflowValidator。
- TopologicalSorter。
- DependencyResolver。

验收：可以正确解析和校验 DAG。

## 阶段二：状态机和数据库执行模型

实现：

- WorkflowStateMachine。
- NodeStateMachine。
- WorkflowExecutionService。
- NodeExecutionService。
- AttemptService。
- ExecutionEventService。

验收：能够持久化工作流和节点状态。

## 阶段三：异步调度和 Worker

实现：

- WorkflowScheduler。
- NodeScheduler。
- RabbitMQ Producer。
- RabbitMQ Consumer。
- NodeExecutor。
- Outbox Publisher。

验收：能够异步执行 HTTP 和 CODE 节点。

## 阶段四：并行调度与重试

实现：

- DependencyResolver。
- 并发状态转换。
- RetryPolicy。
- RetryScheduler。
- 错误分类。

验收：支持并行执行和失败重试。

## 阶段五：租约与故障恢复

实现：

- LeaseManager。
- Heartbeat。
- Fencing Token。
- RecoveryScheduler。

验收：Worker 崩溃后能够恢复执行。

## 阶段六：Agent 协同

实现：

- AgentNodeExecutor。
- AgentInvocationService。
- AgentEventHandler。
- AgentResume。
- Checkpoint 查询和恢复。

验收：支持 Agent 异步执行、人工中断和恢复。

## 阶段七：完善工程能力

实现：

- 工作流取消。
- 失败传播。
- 执行事件查询。
- SSE 进度推送。
- OpenTelemetry 链路追踪。
- 性能测试和故障注入。

验收：完成完整的 GitHub 技术尽调工作流。

---

# 17. 本阶段核心技术亮点

FlowPilot 的执行引擎值得重点展示的不是简单使用了 Spring Boot、RabbitMQ 或 Redis，而是这些技术如何组合解决实际问题。

建议将以下六项作为项目的核心技术亮点：

**1. 基于 DAG 的并行工作流调度**

使用 Kahn 拓扑排序进行环检测和拓扑分析，通过依赖判定实现并行节点调度。

**2. 基于状态机的任务生命周期管理**

将节点状态转换集中管理，避免多个模块随意修改执行状态。

**3. 基于 Transactional Outbox 的可靠消息投递**

通过数据库事务和 Outbox 解决业务状态更新与消息发送之间的双写问题。

**4. 基于幂等键和唯一约束的重复消息处理**

面对 RabbitMQ 至少一次投递，保证业务执行效果不会因重复消息而重复产生。

**5. 基于 Lease 和 Fencing Token 的 Worker 故障恢复**

通过租约管理执行权，通过 Fencing Token 阻止旧 Worker 提交过期结果。

**6. 基于 LangGraph Checkpoint 的 Agent 中断与恢复**

让 Agent 能够长时间运行、等待人工审批，并在服务异常后继续执行。

# 18. 总结

FlowPilot 执行引擎的核心并不是执行某一个任务，而是管理一组存在依赖关系、可能并行执行、可能失败、可能暂停、可能恢复的任务。

整个系统可以概括为：

\[
\boxed{
DAG + StateMachine + MessageQueue + Persistence + Recovery
}
\]

其中：

- DAG 决定任务之间的依赖关系。
- StateMachine 决定任务生命周期。
- MessageQueue 负责异步任务分发。
- Persistence 保存执行事实。
- Recovery 负责系统异常后的任务恢复。

最终，FlowPilot 希望实现的不是一个只能运行一次的工作流 Demo，而是一个具备基本可靠性保障的长时间任务执行平台。

这才是它作为后端工程项目最值得深入实现的部分。