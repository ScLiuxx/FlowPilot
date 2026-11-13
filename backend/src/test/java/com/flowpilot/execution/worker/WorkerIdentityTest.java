package com.flowpilot.execution.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the identity of a worker.
 *
 * <p>The worker records the owner of a lease, and the logs and the metrics of two replicas with
 * the same name cannot be told apart. The identity therefore has to be unique per replica: the
 * hostname of the container is used, which Docker sets to the unique container id.
 *
 * <p>The test reads the configuration files of the application instead of repeating the
 * expression of the placeholder: the profile specific file overrides the common one, so a fixed
 * name in {@code application-worker.yml} would silently win over the common configuration.
 */
class WorkerIdentityTest {

    private static final String WORKER_ID_PROPERTY = "flowpilot.worker.id";

    private static final String FIXED_FALLBACK = "worker-01";

    /**
     * The configuration of the worker profile, with the properties of the process in front of it.
     * The property sources of the JVM are removed, so the result does not depend on the machine
     * the test runs on.
     */
    private StandardEnvironment workerEnvironment(Map<String, Object> process) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // Every source is put in front of the previous one, so the profile specific file has a
        // higher precedence than the common one, exactly as Spring Boot resolves the
        // configuration.
        for (String name : List.of("application.yml", "application-worker.yml")) {
            try {
                List<PropertySource<?>> sources = loader.load(name, new ClassPathResource(name));
                sources.forEach(source -> env.getPropertySources().addFirst(source));
            } catch (IOException e) {
                throw new IllegalStateException("the configuration " + name + " cannot be read", e);
            }
        }
        // The properties of the process have the highest precedence.
        env.getPropertySources().addFirst(new MapPropertySource("process", process));
        return env;
    }

    private String identityOf(Map<String, Object> process) {
        return workerEnvironment(process).getProperty(WORKER_ID_PROPERTY);
    }

    @Test
    @DisplayName("the identity of a replica is its hostname")
    void theIdentityOfAReplicaIsItsHostname() {
        String id = identityOf(Map.of("HOSTNAME", "flowpilot-backend-worker-2"));
        assertThat(id).isEqualTo("flowpilot-backend-worker-2");
    }

    @Test
    @DisplayName("two replicas of the same service have different identities")
    void twoReplicasHaveDifferentIdentities() {
        String first = identityOf(Map.of("HOSTNAME", "flowpilot-backend-worker-1"));
        String second = identityOf(Map.of("HOSTNAME", "flowpilot-backend-worker-2"));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("an explicit identity wins over the hostname")
    void anExplicitIdentityWins() {
        String id = identityOf(Map.of(
                "HOSTNAME", "flowpilot-backend-worker-2",
                "WORKER_ID", "worker-pinned"));
        assertThat(id).isEqualTo("worker-pinned");
    }

    @Test
    @DisplayName("the worker profile does not pin a fixed identity")
    void theWorkerProfileDoesNotPinAFixedIdentity() {
        // A fixed name in the profile specific file would override the common configuration and
        // all replicas would share the same identity.
        String id = identityOf(Map.of("HOSTNAME", "flowpilot-backend-worker-2"));
        assertThat(id).isNotEqualTo(FIXED_FALLBACK);
    }

    @Test
    @DisplayName("the identity falls back to a fixed name without a hostname")
    void theIdentityFallsBackToAFixedName() {
        // A worker that does not run in a container has no container id to use.
        String id = identityOf(Map.of());
        assertThat(id).isEqualTo(FIXED_FALLBACK);
    }
}
