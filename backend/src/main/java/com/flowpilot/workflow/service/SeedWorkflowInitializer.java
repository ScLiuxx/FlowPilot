package com.flowpilot.workflow.service;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Publishes the bundled example workflow on start-up.
 *
 * <p>The seed goes through the regular create and publish path, so the definition hash is
 * computed by the same code that a user created workflow goes through.
 *
 * <p>The seed is only created when it does not exist yet. When the bundled definition changed
 * since the last start-up, a new version is published: a published version can never be changed,
 * so the seed has to be versioned like any other workflow. A definition that is identical to the
 * latest published one is left alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedWorkflowInitializer implements ApplicationRunner {

    private static final String SEED_RESOURCE = "seed/github-due-diligence.json";

    private final WorkflowService workflowService;

    @Value("${flowpilot.seed.enabled:true}")
    private boolean enabled;

    @Value("${flowpilot.seed.owner-id:1000000000000000000}")
    private Long ownerId;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            WorkflowDefinitionDocument document = loadSeed();
            List<com.flowpilot.workflow.entity.WorkflowDefinition> existing =
                    workflowService.page(1, 50, null, document.getName(), null).getRecords();
            if (existing.isEmpty()) {
                var definition = workflowService.create(document.getName(), document.getDescription(),
                        "GITHUB_DUE_DILIGENCE", ownerId, document);
                var version = workflowService.listVersions(definition.getId()).get(0);
                workflowService.publish(definition.getId(), version.getId(), ownerId);
                log.info("the seed workflow was published, workflowId={}", definition.getId());
                return;
            }
            publishNewVersionIfChanged(existing.get(0).getId(), document);
        } catch (Exception ex) {
            // A failing seed must not stop the platform from starting.
            log.warn("the seed workflow could not be published: {}", ex.getMessage());
        }
    }

    /**
     * Publishes a new version of the seed when the bundled definition changed.
     *
     * <p>The definitions are compared by their hash, which is the same comparison the regular
     * version creation makes. An unchanged seed therefore does not produce a new version on every
     * start-up.
     */
    private void publishNewVersionIfChanged(Long workflowId, WorkflowDefinitionDocument document) {
        String hash = JsonUtils.sha256Hex(JsonUtils.canonicalize(document));
        boolean known = workflowService.listVersions(workflowId).stream()
                .anyMatch(version -> hash.equals(version.getDefinitionHash()));
        if (known) {
            log.info("the seed workflow already exists and is up to date, skipping");
            return;
        }
        var version = workflowService.createVersion(workflowId, document, ownerId);
        workflowService.publish(workflowId, version.getId(), ownerId);
        log.info("the seed workflow changed, version {} was published, workflowId={}",
                version.getVersion(), workflowId);
    }

    private WorkflowDefinitionDocument loadSeed() {
        try (InputStream in = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
            return JsonUtils.mapper().readValue(in, WorkflowDefinitionDocument.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read the seed workflow " + SEED_RESOURCE, ex);
        }
    }
}
