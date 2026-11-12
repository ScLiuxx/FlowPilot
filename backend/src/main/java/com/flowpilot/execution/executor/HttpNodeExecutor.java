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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *
 * <h2>What the node collects</h2>
 *
 * <p>A list endpoint is paginated: the node follows the pages of the endpoint up to
 * {@code maxPages} and adds up what it found. The result therefore states both how many items were
 * <em>collected</em> and how many of them were kept for the analysis — a sample is never presented
 * as the total of the repository.
 *
 * <p>The endpoints that answer with an exact number (the search endpoints) report that number as
 * {@code count} and mark the collection as exact.
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

    /** How many pages of a list endpoint are fetched at most. */
    private static final int DEFAULT_MAX_PAGES = 3;

    /** The page size the GitHub API is asked for. */
    private static final int DEFAULT_PER_PAGE = 30;

    /**
     * The GitHub operations the node knows.
     *
     * @param path the path of the endpoint, {@code {owner}} and {@code {repo}} are replaced
     * @param query the query parameters the endpoint is called with
     */
    private record Operation(String path, Map<String, String> query) {
    }

    /**
     * Maps the operation of a collection node onto a GitHub API path.
     *
     * <p>The issues are collected per state: the endpoint answers with the open
     * issues by default, so a single collection would never contain the closed
     * ones and the number of them would be wrong.
     */
    private static final Map<String, Operation> OPERATIONS = Map.ofEntries(
            Map.entry("get_repository",
                    new Operation("/repos/{owner}/{repo}", Map.of())),
            Map.entry("get_commits",
                    new Operation("/repos/{owner}/{repo}/commits", Map.of())),
            Map.entry("get_issues",
                    new Operation("/repos/{owner}/{repo}/issues", Map.of("state", "open"))),
            Map.entry("get_open_issues",
                    new Operation("/repos/{owner}/{repo}/issues", Map.of("state", "open"))),
            Map.entry("get_closed_issues",
                    new Operation("/repos/{owner}/{repo}/issues", Map.of("state", "closed"))),
            Map.entry("get_pull_requests",
                    new Operation("/repos/{owner}/{repo}/pulls", Map.of("state", "all"))),
            Map.entry("get_open_pull_requests",
                    new Operation("/repos/{owner}/{repo}/pulls", Map.of("state", "open"))),
            Map.entry("get_releases",
                    new Operation("/repos/{owner}/{repo}/releases", Map.of())),
            Map.entry("get_contributors",
                    new Operation("/repos/{owner}/{repo}/contributors", Map.of())),
            Map.entry("get_languages",
                    new Operation("/repos/{owner}/{repo}/languages", Map.of())),
            Map.entry("get_dependency_manifest",
                    new Operation("/repos/{owner}/{repo}/contents", Map.of())));

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
        Operation operation;
        String url;
        try {
            operation = resolveOperation(context.getConfig());
            url = resolveUrl(context.getConfig(), context.getInput(), operation);
            assertAllowedTarget(URI.create(url));
        } catch (IllegalArgumentException ex) {
            return NodeResult.failure("HTTP_URL_REJECTED", ErrorCategory.VALIDATION,
                    ex.getMessage(), false);
        }

        int perPage = intConfig(context.getConfig(), "perPage", DEFAULT_PER_PAGE);
        int maxPages = Math.max(1, intConfig(context.getConfig(), "maxPages", DEFAULT_MAX_PAGES));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("statusCode", 200);
        output.put("url", url);
        try {
            output.put("body", collectPages(url, context, perPage, maxPages));
            return NodeResult.success(output);
        } catch (CollectionFailed failed) {
            return failed.getResult();
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
     * Collects a list endpoint page by page.
     *
     * <p>The pages are addressed by the {@code page} parameter of the endpoint — the {@code next}
     * link of the response header is only read to find out whether another page exists, never
     * followed. A header could otherwise point the node at an arbitrary host.
     *
     * <p>The count of the summary is the number of items that were <em>collected</em>. It is the
     * total of the repository only when the last page was reached; the metadata states which of
     * the two it is.
     */
    private Map<String, Object> collectPages(String url, NodeContext context,
                                             int perPage, int maxPages) throws IOException, InterruptedException {
        List<Object> collected = new ArrayList<>();
        int pagesFetched = 0;
        int lastPage = 0;
        boolean hasNext = false;

        for (int page = 1; page <= maxPages; page++) {
            String pageUrl = withPage(withQuery(url, Map.of("per_page", String.valueOf(perPage))), page);
            HttpResponse<String> response = httpClient.send(buildRequest(pageUrl, context),
                    HttpResponse.BodyHandlers.ofString());
            NodeResult failure = checkStatus(response);
            if (failure != null) {
                throw new CollectionFailed(failure);
            }

            com.fasterxml.jackson.databind.JsonNode root =
                    JsonUtils.mapper().readTree(response.body());
            if (!root.isArray()) {
                // An endpoint that answers with an object (the repository for
                // example) holds exactly one item and is never paginated.
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("count", 1);
                summary.put("items", List.of(JsonUtils.mapper().convertValue(root, Map.class)));
                summary.put("truncated", false);
                summary.put("collection", collectionMeta(1, 1, true, false, 1, 1));
                return summary;
            }
            root.forEach(collected::add);

            pagesFetched = page;
            String link = response.headers().firstValue("link").orElse(null);
            hasNext = hasNextPage(link);
            lastPage = lastPage(link);
            if (!hasNext) {
                break;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", collected.size());
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < Math.min(collected.size(), MAX_ITEMS); i++) {
            items.add(collected.get(i));
        }
        summary.put("items", items);
        summary.put("truncated", collected.size() > items.size());
        summary.put("collection", collectionMeta(pagesFetched, lastPage, !hasNext,
                collected.size() > items.size(), collected.size(), items.size()));
        return summary;
    }

    /**
     * Describes what was collected, so that a sample is never presented as the total.
     */
    private Map<String, Object> collectionMeta(int pagesFetched, int lastPage, boolean complete,
                                               boolean itemsSampled, int collected, int analyzed) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("source", "list");
        collection.put("collectedAt", Instant.now().toString());
        collection.put("pagesFetched", pagesFetched);
        if (lastPage > 0) {
            collection.put("totalPages", lastPage);
        }
        // The collection is complete when the last page was reached: then the
        // number of collected items is the number of items the repository has.
        collection.put("complete", complete);
        collection.put("countExact", complete);
        collection.put("itemsSampled", itemsSampled);
        collection.put("collected", collected);
        collection.put("analyzed", analyzed);
        return collection;
    }

    /**
     * Builds the request of a single page.
     */
    private HttpRequest buildRequest(String url, NodeContext context) {
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
        return builder.build();
    }

    /**
     * Translates an HTTP status into a node failure.
     *
     * @return the failure, or {@code null} when the response is fine
     */
    private NodeResult checkStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429) {
            return NodeResult.failure("GITHUB_RATE_LIMIT", ErrorCategory.RATE_LIMIT,
                    "GitHub API rate limit reached", true);
        }
        if (status == 401) {
            return NodeResult.failure("GITHUB_UNAUTHORIZED", ErrorCategory.PERMISSION,
                    "GitHub rejected the request credentials", false);
        }
        if (status == 403) {
            // GitHub answers 403 for both a missing scope and an exhausted rate limit.
            return NodeResult.failure("GITHUB_FORBIDDEN", ErrorCategory.RATE_LIMIT,
                    "GitHub refused the request, most likely a rate limit", true);
        }
        if (status == 404) {
            return NodeResult.failure("GITHUB_NOT_FOUND", ErrorCategory.VALIDATION,
                    "The requested GitHub resource does not exist", false);
        }
        if (status >= 500) {
            return NodeResult.failure("GITHUB_SERVER_ERROR", ErrorCategory.TRANSIENT,
                    "GitHub API returned " + status, true);
        }
        if (status >= 400) {
            return NodeResult.failure("GITHUB_CLIENT_ERROR", ErrorCategory.VALIDATION,
                    "GitHub API rejected the request with status " + status, false);
        }
        return null;
    }

    /**
     * Signals that the collection failed: the node result carries the reason.
     */
    private static final class CollectionFailed extends RuntimeException {
        private final transient NodeResult result;

        CollectionFailed(NodeResult result) {
            super(result.getErrorMessage());
            this.result = result;
        }

        NodeResult getResult() {
            return result;
        }
    }

    /**
     * Builds the target url. Either the configuration carries an explicit {@code url}, or it
     * names a GitHub operation whose path is expanded with the owner and the repository.
     */
    private String resolveUrl(Map<String, Object> config, Map<String, Object> input,
                              Operation operation) {
        Object url = config.get("url");
        if (url != null) {
            return render(String.valueOf(url), input);
        }
        String owner = ownerOf(input);
        String repo = repoOf(input);
        String path = operation.path().replace("{owner}", owner).replace("{repo}", repo);
        Map<String, String> query = new LinkedHashMap<>(operation.query());
        String state = stringConfig(config, "state");
        if (state != null && !state.isBlank()) {
            query.put("state", state);
        }
        Integer sinceDays = intConfigOrNull(config, "sinceDays");
        if (sinceDays != null) {
            query.put("since", Instant.now().minus(sinceDays, ChronoUnit.DAYS).toString());
        }
        return withQuery(GITHUB_API + path, query);
    }

    /**
     * Reads the operation of the node.
     */
    private Operation resolveOperation(Map<String, Object> config) {
        Object url = config.get("url");
        if (url != null) {
            // An explicit url is not paginated.
            return new Operation("", Map.of());
        }
        String operation = String.valueOf(config.getOrDefault("operation", "get_repository"));
        Operation resolved = OPERATIONS.get(operation);
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported GitHub operation: " + operation);
        }
        return resolved;
    }

    /**
     * Appends the query parameters to a url.
     */
    private static String withQuery(String url, Map<String, String> query) {
        if (query.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    /**
     * Addresses a single page of a list endpoint.
     */
    private static String withPage(String url, int page) {
        if (page <= 1) {
            return url;
        }
        return withQuery(url, Map.of("page", String.valueOf(page)));
    }

    /**
     * Whether the response announces another page.
     */
    private static boolean hasNextPage(String linkHeader) {
        return linkHeader != null && linkHeader.contains("rel=\"next\"");
    }

    /**
     * The number of the last page, as announced by the response header.
     */
    private static int lastPage(String linkHeader) {
        if (linkHeader == null) {
            return 0;
        }
        for (String part : linkHeader.split(",")) {
            if (!part.contains("rel=\"last\"")) {
                continue;
            }
            int query = part.indexOf('?');
            if (query < 0) {
                continue;
            }
            for (String parameter : part.substring(query).split("[&]")) {
                if (parameter.startsWith("page=")) {
                    try {
                        return Integer.parseInt(parameter.substring("page=".length()));
                    } catch (NumberFormatException ex) {
                        return 0;
                    }
                }
            }
        }
        return 0;
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
        if (value == null) {
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

    private static int intConfig(Map<String, Object> config, String key, int fallback) {
        Integer value = intConfigOrNull(config, key);
        return value == null ? fallback : value;
    }

    private static Integer intConfigOrNull(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String stringConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
