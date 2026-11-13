package com.flowpilot.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.entity.WorkflowDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.mapper.WorkflowDefinitionMapper;
import com.flowpilot.workflow.mapper.WorkflowVersionMapper;
import com.flowpilot.workflow.parser.WorkflowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages workflow definitions and their immutable versions.
 *
 * <p>The separation between a definition and a version is deliberate: an execution must always
 * run against the exact configuration it was started with, even if the workflow is edited and
 * republished while the execution is still running.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";

    /**
     * The owner of the workflows that the platform provides itself.
     *
     * <p>A workflow owned by this account is visible to every user and can be started by every
     * user. It carries the product's entry scenario, so it must not be hidden behind the owner
     * filter.
     */
    public static final Long SYSTEM_OWNER_ID = 1000000000000000000L;

    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional
    public WorkflowDefinition create(String name, String description, String category,
                                     Long ownerId, WorkflowDefinitionDocument document) {
        WorkflowValidator.validateOrThrow(document);

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id(idGenerator.nextId())
                .name(name)
                .description(description)
                .category(category)
                .ownerId(ownerId)
                .latestVersion(1)
                .status(STATUS_DRAFT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        definitionMapper.insert(definition);

        WorkflowVersion version = buildVersion(definition.getId(), 1, document, ownerId);
        versionMapper.insert(version);
        return definition;
    }

    @Transactional
    public WorkflowDefinition update(Long workflowId, String name, String description,
                                     String category, Long operatorId) {
        WorkflowDefinition definition = requireOwned(workflowId, operatorId);
        if (name != null && !name.isBlank()) {
            definition.setName(name);
        }
        definition.setDescription(description);
        definition.setCategory(category);
        definitionMapper.updateById(definition);
        return definition;
    }

    @Transactional
    public void delete(Long workflowId, Long operatorId) {
        WorkflowDefinition definition = requireOwned(workflowId, operatorId);
        definitionMapper.deleteById(definition.getId());
        versionMapper.delete(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, workflowId));
    }

    /**
     * Creates a new draft version. The version number is derived from the current maximum, and a
     * definition that is identical to an existing version is rejected instead of creating a
     * duplicate.
     */
    @Transactional
    public WorkflowVersion createVersion(Long workflowId, WorkflowDefinitionDocument document, Long operatorId) {
        WorkflowDefinition definition = requireOwned(workflowId, operatorId);
        WorkflowValidator.validateOrThrow(document);

        String canonical = JsonUtils.canonicalize(document);
        String hash = JsonUtils.sha256Hex(canonical);

        boolean alreadyKnown = versionMapper.findByWorkflowId(workflowId).stream()
                .anyMatch(version -> hash.equals(version.getDefinitionHash()));
        if (alreadyKnown) {
            throw new BizException(ErrorCode.WF_DUPLICATE_DEFINITION);
        }

        int nextVersion = versionMapper.findMaxVersion(workflowId) + 1;
        WorkflowVersion version = buildVersion(workflowId, nextVersion, document, operatorId);
        versionMapper.insert(version);
        definition.setLatestVersion(nextVersion);
        definitionMapper.updateById(definition);
        return version;
    }

    /**
     * Publishes a version after validating the DAG. A published version can never be changed.
     */
    @Transactional
    public WorkflowVersion publish(Long workflowId, Long versionId, Long operatorId) {
        requireOwned(workflowId, operatorId);
        WorkflowVersion version = versionMapper.selectById(versionId);
        if (version == null || !version.getWorkflowId().equals(workflowId)) {
            throw new BizException(ErrorCode.WF_VERSION_NOT_FOUND);
        }
        if (STATUS_PUBLISHED.equals(version.getStatus())) {
            throw new BizException(ErrorCode.WF_VERSION_IMMUTABLE);
        }
        WorkflowDefinitionDocument document = parse(version.getDefinitionJson());
        WorkflowValidator.validateOrThrow(document);

        version.setStatus(STATUS_PUBLISHED);
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(version);

        WorkflowDefinition definition = definitionMapper.selectById(workflowId);
        definition.setStatus(STATUS_PUBLISHED);
        definition.setLatestVersion(Math.max(definition.getLatestVersion(), version.getVersion()));
        definitionMapper.updateById(definition);
        return version;
    }

    public void setStatus(Long workflowId, Long operatorId, String status) {
        requireOwned(workflowId, operatorId);
        WorkflowDefinition definition = definitionMapper.selectById(workflowId);
        definition.setStatus(status);
        definitionMapper.updateById(definition);
    }

    public WorkflowDefinition get(Long workflowId) {
        WorkflowDefinition definition = definitionMapper.selectById(workflowId);
        if (definition == null) {
            throw new BizException(ErrorCode.WF_NOT_FOUND);
        }
        return definition;
    }

    public WorkflowDefinition requireOwned(Long workflowId, Long ownerId) {
        WorkflowDefinition definition = get(workflowId);
        if (!isAccessible(definition, ownerId)) {
            throw new BizException(ErrorCode.WF_FORBIDDEN);
        }
        return definition;
    }

    /**
     * Whether a user may see and run a workflow. The platform provided workflows are open to
     * everyone, a personal workflow only to its owner.
     */
    public boolean isAccessible(WorkflowDefinition definition, Long userId) {
        return SYSTEM_OWNER_ID.equals(definition.getOwnerId())
                || definition.getOwnerId().equals(userId);
    }

    /**
     * The versions of a workflow that the user may see.
     *
     * <p>The versions carry the whole definition of the workflow, so they need the same check as
     * the workflow itself. Without it a user could read the definition of a personal workflow of
     * somebody else, even though the workflow itself is protected.
     */
    public List<WorkflowVersion> listVersions(Long workflowId, Long ownerId) {
        requireOwned(workflowId, ownerId);
        return listVersionsInternal(workflowId);
    }

    public WorkflowVersion getVersion(Long workflowId, Long versionId, Long ownerId) {
        requireOwned(workflowId, ownerId);
        return getVersionInternal(workflowId, versionId);
    }

    /**
     * The versions of a workflow, without a check of the ownership.
     *
     * <p>Only for the internal paths that already own the workflow: the worker, the scheduler and
     * the recovery scanners. A request of a user has to go through the variants that check the
     * ownership.
     */
    public List<WorkflowVersion> listVersionsInternal(Long workflowId) {
        return versionMapper.findByWorkflowId(workflowId);
    }

    /**
     * A version of a workflow, without a check of the ownership.
     *
     * @see #listVersionsInternal(Long)
     */
    public WorkflowVersion getVersionInternal(Long workflowId, Long versionId) {
        WorkflowVersion version = versionMapper.selectById(versionId);
        if (version == null || !version.getWorkflowId().equals(workflowId)) {
            throw new BizException(ErrorCode.WF_VERSION_NOT_FOUND);
        }
        return version;
    }

    public WorkflowVersion getPublishedVersion(Long workflowId) {
        WorkflowVersion version = versionMapper.findLatestPublished(workflowId);
        if (version == null) {
            throw new BizException(ErrorCode.WF_VERSION_NOT_PUBLISHED);
        }
        return version;
    }

    public PageResult<WorkflowDefinition> page(int page, int size, String status, String keyword, Long ownerId) {
        LambdaQueryWrapper<WorkflowDefinition> query = new LambdaQueryWrapper<WorkflowDefinition>()
                // The platform provided workflows are visible to every user, a personal workflow
                // only to its owner.
                .and(ownerId != null, wrapper -> wrapper.eq(WorkflowDefinition::getOwnerId, ownerId)
                        .or().eq(WorkflowDefinition::getOwnerId, SYSTEM_OWNER_ID))
                .eq(status != null && !status.isBlank(), WorkflowDefinition::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        wrapper -> wrapper.like(WorkflowDefinition::getName, keyword)
                                .or().like(WorkflowDefinition::getDescription, keyword))
                .orderByDesc(WorkflowDefinition::getUpdatedAt);
        Page<WorkflowDefinition> result = definitionMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    public WorkflowDefinitionDocument parse(String definitionJson) {
        return JsonUtils.fromJson(definitionJson, WorkflowDefinitionDocument.class);
    }

    private WorkflowVersion buildVersion(Long workflowId, int versionNumber,
                                         WorkflowDefinitionDocument document, Long operatorId) {
        String canonical = JsonUtils.canonicalize(document);
        return WorkflowVersion.builder()
                .id(idGenerator.nextId())
                .workflowId(workflowId)
                .version(versionNumber)
                .definitionJson(canonical)
                .definitionHash(JsonUtils.sha256Hex(canonical))
                .status(STATUS_DRAFT)
                .createdBy(operatorId)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
