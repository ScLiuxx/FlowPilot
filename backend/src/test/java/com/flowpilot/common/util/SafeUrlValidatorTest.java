package com.flowpilot.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of the URL validation, which is the defence against server side request forgery.
 */
class SafeUrlValidatorTest {

    @Test
    @DisplayName("accepts a GitHub repository url")
    void acceptsGithubRepositoryUrl() {
        SafeUrlValidator.RepoRef ref = SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://github.com/example/project");

        assertThat(ref.owner()).isEqualTo("example");
        assertThat(ref.name()).isEqualTo("project");
    }

    @Test
    @DisplayName("strips the .git suffix")
    void stripsGitSuffix() {
        SafeUrlValidator.RepoRef ref = SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://github.com/example/project.git");

        assertThat(ref.name()).isEqualTo("project");
    }

    @Test
    @DisplayName("rejects a plain http url")
    void rejectsHttpUrl() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl(
                "http://github.com/example/project"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("rejects a host that is not on the allow list")
    void rejectsForeignHost() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://evil.example.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("rejects a url that embeds credentials")
    void rejectsEmbeddedCredentials() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://user:pass@github.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("rejects a non default port")
    void rejectsNonDefaultPort() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://github.com:8443/a/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    @DisplayName("rejects a path that is not a repository")
    void rejectsNonRepositoryPath() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl(
                "https://github.com/example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a blank url")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> SafeUrlValidator.validateGitHubRepositoryUrl("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("treats the private and reserved address ranges as non public")
    void rejectsPrivateAddresses() throws Exception {
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("192.168.1.1"))).isFalse();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("172.16.0.1"))).isFalse();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("0.0.0.0"))).isFalse();
        // Carrier grade NAT, which some clouds use for internal services.
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("100.64.0.1"))).isFalse();
    }

    @Test
    @DisplayName("accepts a public address")
    void acceptsPublicAddress() throws Exception {
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("140.82.121.4"))).isTrue();
        assertThat(SafeUrlValidator.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
}
