package com.flowpilot.execution.service;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.workflow.definition.RetryPolicyDefinition;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds the retry policy a node was declared with.
 *
 * <p>The policy lives in the definition of the workflow, not in the node row, so it has to be
 * read back from the version the execution was started with. Without it the caller falls back to
 * the default policy, and the delays of the node are not the ones its author configured.
 */
@Component
@RequiredArgsConstructor
public class NodeRetryPolicyResolver {

    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowService workflowService;

    /**
     * The retry policy of the node, or {@code null} when the node has none.
     */
    public RetryPolicyDefinition resolve(NodeExecution node) {
        if (node == null) {
            return null;
        }
        WorkflowExecution execution = executionMapper.selectById(node.getWorkflowExecutionId());
        if (execution == null) {
            return null;
        }
        WorkflowVersion version = workflowService.getVersionInternal(
                execution.getWorkflowId(), execution.getWorkflowVersionId());
        WorkflowDefinitionDocument document = workflowService.parse(version.getDefinitionJson());
        for (WorkflowNodeDefinition candidate : document.nodes()) {
            if (candidate.getId().equals(node.getNodeKey())) {
                return candidate.getRetryPolicy();
            }
        }
        return null;
    }
}
