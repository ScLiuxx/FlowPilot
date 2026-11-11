package com.flowpilot.execution.executor;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.common.util.SafeUrlValidator;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.messaging.protocol.ErrorCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the GitHub REST API.
 *
 * <p>Only {@code https://api.github.com} is reachable in this version. The target host is fixed
 * and the resolved address must be public, which closes the server side request forgery hole a
 * user supplied URL would otherwise open. Redirects are never followed, because a redirect is a
 * common way to escape an allow list.
 */
@Slf4j
@Component
public class HttpNodeExecutor implements NodeExecutor {

    static final String GITHUB_API = "https://api.github.com";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** How many entries of a list response are kept in the node output. */
    private static final int MAX_ITEMS = 30;

    /** How many characters of a non JSON response are kept in the node output. */
    private static final int MAX_BODY_CHARS = 8 * 1024;

    /** Maps the operation of a collection node onto a GitHub API path. */
    private static final Map<String, String> OPERATIONS = Map.of(
            "get_repository", "/repos/{owner}/{repo}",
            "get_commits", "/repos/{owner}/{repo}/commits",
            "get_issues", "/repos/{owner}/{repo}/issues",
            "get_pull_requests", "/repos/{owner}/{repo}/pulls",
            "get_releases", "/repos/{owner}/{repo}/releases",
            "get_contributors", "/repos/{owner}/{repo}/contributors",
            "get_languages", "/repos/{owner}/{repo}/languages",
            "get_dependency_manifest", "/repos/{owner}/{repo}/contents");

    private final HttpClient httpClient;
    private final String githubToken;

    public HttpNodeExecutor(@Value("${flowpilot.github.token:}") String githubToken) {
        this.githubToken = githubToken;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Redirects are not followed: a redirect is a common way to escape the allow list.
                .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxy = proxySelector();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        this.httpClient = builder.build();
    }

    /**
     * Reads the proxy from the standard JVM system properties.
     *
     * <p>Enterprise deployments usually have to go through a corporate proxy, and the JVM
     * properties are the conventional way to configure one. The proxy is only a transport, the
     * target host is still validated against the allow list.
     */
    private static ProxySelector proxySelector() {
        String host = System.getProperty("http.proxyHost");
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = Integer.parseInt(System.getProperty("http.proxyPort", "8080"));
        String user = System.getProperty("http.proxyUser");
        String password = System.getProperty("http.proxyPassword");
        log.info("the HTTP node uses the proxy {}:{}", host, port);

        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(host, port)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress socketAddress, IOException ex) {
                log.warn("the proxy connection failed for {}: {}", uri, ex.getMessage());
            }
        };
    }

    @Override
    public NodeType supportType() {
        return NodeType.HTTP;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String url;
        try {
            url = resolveUrl(context.getConfig(), context.getInput());
            assertAllowedTarget(URI.create(url));
        } catch (IllegalArgumentException ex) {
            return NodeResult.failure("HTTP_URL_REJECTED", ErrorCategory.VALIDATION,
                    ex.getMessage(), false);
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(context.getTimeoutSeconds() == null
                            ? DEFAULT_TIMEOUT : Duration.ofSeconds(context.getTimeoutSeconds()))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FlowPilot-Agent")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET();

            String token = this.githubToken;
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return NodeResult.failure("GITHUB_RATE_LIMIT", ErrorCategory.RATE_LIMIT,
                        "GitHub API rate limit reached", true);
            }
            if (response.statusCode() == 401) {
                return NodeResult.failure("GITHUB_UNAUTHORIZED", ErrorCategory.PERMISSION,
                        "GitHub rejected the request credentials", false);
            }
            if (response.statusCode() == 403) {
                // GitHub answers 403 for both a missing scope and an exhausted rate limit.
                return NodeResult.failure("GITHUB_FORBIDDEN", ErrorCategory.RATE_LIMIT,
                        "GitHub refused the request, most likely a rate limit", true);
            }
            if (response.statusCode() == 404) {
                return NodeResult.failure("GITHUB_NOT_FOUND", ErrorCategory.VALIDATION,
                        "The requested GitHub resource does not exist", false);
            }
            if (response.statusCode() >= 500) {
                return NodeResult.failure("GITHUB_SERVER_ERROR", ErrorCategory.TRANSIENT,
                        "GitHub API returned " + response.statusCode(), true);
            }
            if (response.statusCode() >= 400) {
                return NodeResult.failure("GITHUB_CLIENT_ERROR", ErrorCategory.VALIDATION,
                        "GitHub API rejected the request with status " + response.statusCode(), false);
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("statusCode", response.statusCode());
            output.put("url", url);
            // The raw response can be megabytes large, and the node output is stored in the
            // database. Only a bounded summary is kept, so the rows stay small and the report
            // remains reproducible.
            output.put("body", summarize(response.body()));
            return NodeResult.success(output);
        } catch (java.net.http.HttpTimeoutException ex) {
            return NodeResult.failure("HTTP_TIMEOUT", ErrorCategory.TRANSIENT,
                    "The HTTP request timed out", true);
        } catch (java.io.IOException ex) {
            return NodeResult.failure("HTTP_IO_ERROR", ErrorCategory.TRANSIENT,
                    "The HTTP request failed: " + ex.getMessage(), true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return NodeResult.failure("HTTP_INTERRUPTED", ErrorCategory.INTERNAL,
                    "The HTTP request was interrupted", false);
        }
    }

    /**
     * Builds the target url. Either the configuration carries an explicit {@code url}, or it
     * names a GitHub operation whose path is expanded with the owner and the repository.
     */
    private String resolveUrl(Map<String, Object> config, Map<String, Object> input) {
        Object url = config.get("url");
        if (url != null) {
            return render(String.valueOf(url), input);
        }
        String operation = String.valueOf(config.getOrDefault("operation", "get_repository"));
        String path = OPERATIONS.get(operation);
        if (path == null) {
            throw new IllegalArgumentException("Unsupported GitHub operation: " + operation);
        }
        String owner = ownerOf(input);
        String repo = repoOf(input);
        return GITHUB_API + path.replace("{owner}", owner).replace("{repo}", repo);
    }

    /**
     * Reads the repository owner. It is either given explicitly or derived from the repository
     * url the user submitted.
     */
    private String ownerOf(Map<String, Object> input) {
        String owner = input.get("owner") == null ? null : String.valueOf(input.get("owner"));
        if (owner != null && !owner.isBlank()) {
            return owner;
        }
        return require(input, "owner");
    }

    /**
     * Reads the repository name. It is either given explicitly or derived from the repository
     * url the user submitted.
     */
    private String repoOf(Map<String, Object> input) {
        String repo = input.get("repo") == null ? null : String.valueOf(input.get("repo"));
        if (repo != null && !repo.isBlank()) {
            return repo;
        }
        return require(input, "repo");
    }

    private String require(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("The HTTP node requires the input '" + key + "'");
        }
        return String.valueOf(value);
    }

    /**
     * Replaces the {@code {{placeholder}}} tokens of a template with the node input.
     */
    private String render(String template, Map<String, Object> input) {
        String rendered = template;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }

    private void assertAllowedTarget(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only https requests are allowed");
        }
        String host = uri.getHost();
        if (host == null || !"api.github.com".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Host is not allowed: " + host);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Only the default https port is allowed");
        }
        SafeUrlValidator.assertPublicHost("api.github.com");
    }

    /**
     * Reduces a response body to a bounded summary.
     *
     * <p>A list response keeps the total count and the first entries, an object response keeps
     * its entries, and a body that is not JSON keeps a truncated excerpt.
     */
    private Object summarize(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JsonUtils.mapper().readTree(body);
            if (root.isArray()) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("count", root.size());
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < Math.min(root.size(), MAX_ITEMS); i++) {
                    items.add(JsonUtils.mapper().convertValue(root.get(i), Map.class));
                }
                summary.put("items", items);
                summary.put("truncated", root.size() > MAX_ITEMS);
                return summary;
            }
            if (root.isObject()) {
                return JsonUtils.mapper().convertValue(root, Map.class);
            }
            return root.asText();
        } catch (Exception ex) {
            return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
        }
    }
}
