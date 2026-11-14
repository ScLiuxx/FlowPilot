package com.flowpilot.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.execution.entity.NodeExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Mapper
public interface NodeExecutionMapper extends BaseMapper<NodeExecution> {

    /**
     * Reads the nodes of an execution.
     *
     * <p>The identifiers are selected first and the rows are read afterwards: the output of a node
     * carries the raw response of an external system and can be larger than the sort buffer of the
     * database, which would make the sort fail with "out of sort memory".
     */
    @Select("SELECT id FROM node_execution WHERE workflow_execution_id = #{executionId} ORDER BY id")
    List<Long> findIdsByWorkflowExecutionId(@Param("executionId") Long executionId);

    @Select("""
            <script>
            SELECT * FROM node_execution WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<NodeExecution> selectByIds(@Param("ids") List<Long> ids);

    default List<NodeExecution> findByWorkflowExecutionId(Long executionId) {
        List<Long> ids = findIdsByWorkflowExecutionId(executionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return inOrder(ids, selectByIds(ids));
    }

    /**
     * Returns the rows in the order of their identifiers.
     */
    static List<NodeExecution> inOrder(List<Long> ids, List<NodeExecution> rows) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, NodeExecution> byId = rows.stream()
                .collect(Collectors.toMap(NodeExecution::getId, Function.identity(), (first, second) -> first));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * Reads the node states of an execution without the JSON payloads.
     *
     * <p>The input and the output of a node can be large, because they carry the raw API
     * responses. Loading them for a list query would sort megabytes of JSON in the database for
     * no reason, so the list and the scheduling queries use this projection instead.
     */
    @Select("""
            SELECT id, workflow_execution_id, node_key, node_type, status,
                   current_attempt, max_attempts, worker_id, lease_token,
                   error_code, error_message, version, started_at, finished_at
              FROM node_execution
             WHERE workflow_execution_id = #{executionId}
             ORDER BY id
            """)
    List<NodeExecution> findStatesByWorkflowExecutionId(@Param("executionId") Long executionId);

    @Select("SELECT * FROM node_execution WHERE workflow_execution_id = #{executionId} AND node_key = #{nodeKey}")
    NodeExecution findByExecutionAndKey(@Param("executionId") Long executionId,
                                        @Param("nodeKey") String nodeKey);

    /**
     * Reads a node that belongs to the given execution.
     *
     * <p>The execution is part of the lookup, so a node of somebody else's execution cannot be
     * read through the identifier of an own execution.
     */
    @Select("SELECT * FROM node_execution WHERE id = #{nodeId} AND workflow_execution_id = #{executionId}")
    NodeExecution findByIdAndExecutionId(@Param("nodeId") Long nodeId,
                                         @Param("executionId") Long executionId);

    /**
     * Marks a pending node as ready and stores its input.
     *
     * <p>The input is persisted so the worker reads the same values the scheduler computed, even
     * if the message is redelivered much later.
     *
     * <p>Only one concurrent scheduler can win this update, which is what prevents a node from
     * being dispatched twice.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'READY',
                   input_json = #{inputJson},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int markReady(@Param("id") Long id, @Param("inputJson") String inputJson);

    /**
     * Releases a node whose retry delay elapsed back into the ready state.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'READY',
                   next_retry_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RETRY_WAIT'
            """)
    int markRetryReady(@Param("id") Long id);

    /**
     * Releases a running agent node whose lease expired back into the ready state.
     *
     * <p>An agent node is not in the retry wait state when its worker dies: it is still in the
     * running state, because the agent service owns it and not a Java worker. The recovery
     * therefore needs its own transition. Without it the redispatch would find no node to
     * take over and the agent node would stay in the running state for ever.
     *
     * <p>The attempt number is rolled back by one. The redispatch derives the attempt number
     * from it, and only that way it finds the invocation of the interrupted attempt again: the
     * agent therefore continues on the same thread and from the same checkpoint.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'READY',
                   current_attempt = #{attemptNo} - 1,
                   worker_id = NULL,
                   lease_expire_at = NULL,
                   next_retry_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND current_attempt = #{attemptNo}
            """)
    int markAgentRecoveryReady(@Param("id") Long id, @Param("attemptNo") Integer attemptNo);

    /**
     * Acquires the execution lease. The fencing token is incremented so a previous owner of the
     * lease can no longer commit a result.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'RUNNING',
                   worker_id = #{workerId},
                   lease_token = lease_token + 1,
                   lease_expire_at = #{leaseExpireAt},
                   heartbeat_at = CURRENT_TIMESTAMP(3),
                   current_attempt = #{attemptNo},
                   started_at = COALESCE(started_at, CURRENT_TIMESTAMP(3)),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status IN ('READY', 'RETRY_WAIT')
               AND current_attempt = #{expectedAttempt}
            """)
    int acquireLease(@Param("id") Long id,
                     @Param("workerId") String workerId,
                     @Param("attemptNo") Integer attemptNo,
                     @Param("expectedAttempt") Integer expectedAttempt,
                     @Param("leaseExpireAt") LocalDateTime leaseExpireAt);

    /**
     * Extends the lease while the worker is still executing.
     *
     * @return 0 when the worker lost the lease and must stop its work
     */
    @Update("""
            UPDATE node_execution
               SET heartbeat_at = CURRENT_TIMESTAMP(3),
                   lease_expire_at = #{leaseExpireAt},
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND worker_id = #{workerId}
               AND lease_token = #{leaseToken}
            """)
    int heartbeat(@Param("id") Long id,
                  @Param("workerId") String workerId,
                  @Param("leaseToken") Long leaseToken,
                  @Param("leaseExpireAt") LocalDateTime leaseExpireAt);

    /**
     * Commits a successful result. The status, attempt and fencing token must all still match,
     * otherwise a stale worker could overwrite a newer result.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'SUCCEEDED',
                   output_json = #{outputJson},
                   error_code = NULL,
                   error_message = NULL,
                   finished_at = CURRENT_TIMESTAMP(3),
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND current_attempt = #{attemptNo}
               AND lease_token = #{leaseToken}
            """)
    int completeSuccessfully(@Param("id") Long id,
                             @Param("attemptNo") Integer attemptNo,
                             @Param("leaseToken") Long leaseToken,
                             @Param("outputJson") String outputJson);

    /**
     * Marks the node as waiting for a retry.
     *
     * <p>The moment the node may be retried is stored with the state, so the delay survives a
     * restart and the scheduler can pick the node up exactly when it is due.
     *
     * <p>The worker of the failed attempt is cleared: the worker is done with the node, and the
     * ready scanner only picks up the nodes that no worker owns.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'RETRY_WAIT',
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   next_retry_at = #{nextRetryAt},
                   worker_id = NULL,
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND current_attempt = #{attemptNo}
               AND lease_token = #{leaseToken}
            """)
    int markRetryWait(@Param("id") Long id,
                      @Param("attemptNo") Integer attemptNo,
                      @Param("leaseToken") Long leaseToken,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * Marks the node as terminally failed.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'FAILED',
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   finished_at = CURRENT_TIMESTAMP(3),
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND current_attempt = #{attemptNo}
               AND lease_token = #{leaseToken}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("attemptNo") Integer attemptNo,
                   @Param("leaseToken") Long leaseToken,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /**
     * Moves a node into the human waiting state, used when an agent interrupts itself.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'WAITING_HUMAN',
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND current_attempt = #{attemptNo}
            """)
    int markWaitingHuman(@Param("id") Long id,
                         @Param("attemptNo") Integer attemptNo);

    /**
     * Returns a waiting node to the ready state after an approval was granted.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'READY',
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'WAITING_HUMAN'
            """)
    int releaseFromHumanWait(@Param("id") Long id);

    /**
     * Puts a waiting node back into the running state.
     *
     * <p>This is the transition an agent node makes when the reviewer approved and the agent
     * continues on the same thread. The attempt number and the fencing token stay the same, so
     * the result the agent reports afterwards is accepted.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'RUNNING',
                   lease_expire_at = #{leaseExpireAt},
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'WAITING_HUMAN'
            """)
    int resumeRunningFromHumanWait(@Param("id") Long id,
                                   @Param("leaseExpireAt") LocalDateTime leaseExpireAt);

    /**
     * Marks a node that waits for a human as failed, used when the reviewer rejected it.
     *
     * <p>The node is not running any more, so the lease and the attempt number of the running
     * state do not apply here.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'FAILED',
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   finished_at = CURRENT_TIMESTAMP(3),
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'WAITING_HUMAN'
            """)
    int markFailedFromHumanWait(@Param("id") Long id,
                                 @Param("errorCode") String errorCode,
                                 @Param("errorMessage") String errorMessage);

    /**
     * Completes a node that waited for a human decision.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'SUCCEEDED',
                   output_json = #{outputJson},
                   finished_at = CURRENT_TIMESTAMP(3),
                   lease_expire_at = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'WAITING_HUMAN'
            """)
    int completeFromHumanWait(@Param("id") Long id, @Param("outputJson") String outputJson);

    /**
     * Cancels a node that has not started yet.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'CANCELED',
                   finished_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status IN ('PENDING', 'READY', 'RETRY_WAIT', 'WAITING_HUMAN')
            """)
    int cancel(@Param("id") Long id);

    /**
     * Skips a node because the branch of its condition was not selected.
     */
    @Update("""
            UPDATE node_execution
               SET status = 'SKIPPED',
                   finished_at = CURRENT_TIMESTAMP(3),
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int skip(@Param("id") Long id);

    /**
     * The running nodes whose lease expired.
     *
     * <p>Only the identifiers are selected, because the sort would carry the output of the nodes
     * through the sort buffer of the database.
     */
    @Select("""
            SELECT id FROM node_execution
             WHERE status = 'RUNNING'
               AND lease_expire_at IS NOT NULL
               AND lease_expire_at < #{now}
             ORDER BY lease_expire_at
             LIMIT #{limit}
            """)
    List<Long> findExpiredLeaseIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    default List<NodeExecution> findExpiredLeases(LocalDateTime now, int limit) {
        List<Long> ids = findExpiredLeaseIds(now, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        return inOrder(ids, selectByIds(ids));
    }

    /**
     * The nodes that wait for their retry.
     *
     * <p>Only the identifiers are selected, for the same reason as in {@link #findExpiredLeaseIds}.
     */
    @Select("""
            SELECT id FROM node_execution
             WHERE status = 'RETRY_WAIT'
               AND (next_retry_at IS NULL OR next_retry_at <= #{now})
             ORDER BY next_retry_at
             LIMIT #{limit}
            """)
    List<Long> findRetryWaitingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    default List<NodeExecution> findRetryWaiting(LocalDateTime now, int limit) {
        List<Long> ids = findRetryWaitingIds(now, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        return inOrder(ids, selectByIds(ids));
    }

    /**
     * The ready nodes that no worker took over.
     *
     * <p>A node that is ready and has no worker was never executed. The timestamp is the moment
     * the node became ready, which is the moment the execution message was written.
     */
    @Select("""
            SELECT id FROM node_execution
             WHERE status = 'READY'
               AND (worker_id IS NULL OR worker_id = '')
               AND updated_at <= #{deadline}
             ORDER BY updated_at
             LIMIT #{limit}
            """)
    List<Long> findReadyWithoutWorkerIds(@Param("deadline") LocalDateTime deadline,
                                          @Param("limit") int limit);

    default List<NodeExecution> findReadyWithoutWorker(LocalDateTime deadline, int limit) {
        List<Long> ids = findReadyWithoutWorkerIds(deadline, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        return inOrder(ids, selectByIds(ids));
    }

    /**
     * The number of the nodes of the executions of one user.
     *
     * <p>The owner is {@code null} for the administrator, who sees the nodes of everybody.
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM node_execution n
              JOIN workflow_execution e ON e.id = n.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
            </script>
            """)
    long countByOwner(@Param("userId") Long userId);

    /**
     * The states of the nodes of the executions of one user.
     *
     * <p>Only the states are read, the payloads of the nodes are not needed for the statistics.
     */
    @Select("""
            <script>
            SELECT n.id, n.workflow_execution_id, n.node_key, n.node_type, n.status,
                   n.current_attempt, n.max_attempts, n.worker_id, n.lease_token,
                   n.error_code, n.error_message, n.version, n.started_at, n.finished_at
              FROM node_execution n
              JOIN workflow_execution e ON e.id = n.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
            </script>
            """)
    List<NodeExecution> findStatesByOwner(@Param("userId") Long userId);

    /**
     * Extends the lease of a running node without changing the worker.
     *
     * <p>The recovery scanner uses it when the agent service reports that the agent is still
     * working. The node keeps its worker and its fencing token, so the result of the running
     * attempt is still accepted.
     */
    @Update("""
            UPDATE node_execution
               SET lease_expire_at = #{leaseExpireAt},
                   heartbeat_at = CURRENT_TIMESTAMP(3),
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND lease_token = #{leaseToken}
            """)
    int extendLease(@Param("id") Long id,
                    @Param("leaseToken") Long leaseToken,
                    @Param("leaseExpireAt") LocalDateTime leaseExpireAt);

    /**
     * Claims a ready node for another dispatch.
     *
     * <p>The update is conditional on the state of the node, so two scanners cannot dispatch the
     * same node twice. The timestamp is moved forward, so the node is not picked up again
     * immediately.
     */
    @Update("""
            UPDATE node_execution
               SET updated_at = CURRENT_TIMESTAMP(3),
                   version = version + 1
             WHERE id = #{id}
               AND status = 'READY'
               AND current_attempt = #{expectedAttempt}
               AND (worker_id IS NULL OR worker_id = '')
            """)
    int claimReadyForReschedule(@Param("id") Long id,
                                 @Param("expectedAttempt") Integer expectedAttempt);
}
