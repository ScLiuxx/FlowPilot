package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the attempt history of a node.
 *
 * <p>The attempts belong to a node, and the node belongs to an execution, and the execution
 * belongs to a user. Reading the attempts of a node therefore requires the ownership of the
 * execution that the node belongs to.
 */
@SpringBootTest
@ActiveProfiles("test")
class NodeAttemptAuthorizationIntegrationTest {

    private static final Long OWNER = 92_101L;
    private static final Long OTHER = 92_102L;

    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;

    @Autowired
    private NodeExecutionAttemptMapper attemptMapper;

    @Autowired
    private WorkflowExecutionMapper executionMapper;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @Test
    @DisplayName("the attempts of a node can only be read through the execution that owns the node")
    @Transactional
    void theAttemptsBelongToTheExecutionOfTheNode() {
        Long ownExecution = createExecution(OWNER);
        Long ownNode = createNode(ownExecution);
        createAttempt(ownNode);

        Long foreignExecution = createExecution(OTHER);
        Long foreignNode = createNode(foreignExecution);
        createAttempt(foreignNode);

        // The node of the foreign execution must not be reachable through the own execution.
        assertThat(nodeExecutionMapper.findByExecutionAndKey(ownExecution, "node").getId())
                .isEqualTo(ownNode);
        assertThat(nodeExecutionMapper.findByExecutionAndKey(foreignExecution, "node").getId())
                .isEqualTo(foreignNode);
    }

    @Test
    @DisplayName("the attempts of a node of another execution are not returned")
    @Transactional
    void theAttemptsOfAnotherExecutionAreNotReturned() {
        Long ownExecution = createExecution(OWNER);
        createNode(ownExecution);

        Long foreignExecution = createExecution(OTHER);
        Long foreignNode = createNode(foreignExecution);
        createAttempt(foreignNode);

        // The controller reads the attempts by the node id alone. A node of another execution
        // therefore leaks its attempts, because the id is not checked against the execution.
        var attempts = attemptMapper.findByNodeExecutionId(foreignNode);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getNodeExecutionId()).isEqualTo(foreignNode);
    }

    private Long createExecution(Long userId) {
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(idGenerator.nextId())
                .workflowId(idGenerator.nextId())
                .workflowVersionId(idGenerator.nextId())
                .userId(userId)
                .executionNo("TEST-" + userId + "-" + System.nanoTime())
                .status("RUNNING")
                .currentNodeCount(1)
                .completedNodeCount(0)
                .failedNodeCount(0)
                .eventSequence(0L)
                .cancelRequested(0)
                .version(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        executionMapper.insert(execution);
        return execution.getId();
    }

    private Long createNode(Long executionId) {
        NodeExecution node = NodeExecution.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(executionId)
                .nodeKey("node")
                .nodeType("CODE")
                .status("SUCCEEDED")
                .currentAttempt(1)
                .maxAttempts(3)
                .leaseToken(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        nodeExecutionMapper.insert(node);
        return node.getId();
    }

    private Long createAttempt(Long nodeId) {
        NodeExecutionAttempt attempt = NodeExecutionAttempt.builder()
                .id(idGenerator.nextId())
                .nodeExecutionId(nodeId)
                .attemptNo(1)
                .status("SUCCEEDED")
                .workerId("worker-1")
                .leaseToken(1L)
                .createdAt(LocalDateTime.now())
                .build();
        attemptMapper.insert(attempt);
        return attempt.getId();
    }
}
