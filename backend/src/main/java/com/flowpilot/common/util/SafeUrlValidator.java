package com.flowpilot.common.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates user supplied URLs before the backend reaches out to them.
 *
 * <p>The product scenario lets a user paste a GitHub repository URL, which means the backend will
 * actively fetch an external resource. Without this check that becomes a server side request
 * forgery primitive against the cluster's own network.
 *
 * <p>Defences applied here:
 * <ul>
 *   <li>Only {@code https} is accepted, plain {@code http} is rejected.</li>
 *   <li>Only hostnames on the allow list are accepted, so no user controlled host is ever
 *       resolved.</li>
 *   <li>The resolved IP address must not belong to a private, loopback, link local, multicast or
 *       otherwise reserved range. This blocks DNS rebinding towards the cloud metadata service.</li>
 * </ul>
 */
public final class SafeUrlValidator {

    /** GitHub is the only external source the V1 workflow is allowed to reach. */
    public static final Set<String> ALLOWED_HOSTS = Set.of("github.com", "www.github.com", "api.github.com");

    private static final Pattern GITHUB_REPO_PATH =
            Pattern.compile("^/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");

    private SafeUrlValidator() {
    }

    public record RepoRef(String owner, String name) {
    }

    /**
     * @throws IllegalArgumentException when the url is not an acceptable GitHub repository url
     */
    public static RepoRef validateGitHubRepositoryUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL must not be blank");
        }
        String url = rawUrl.trim();
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("Only https:// repository URLs are allowed");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Repository URL is not a valid URI", ex);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Repository URL must contain a host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new IllegalArgumentException("Host is not allowed: " + host);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Repository URL must not contain credentials");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Only the default https port is allowed");
        }
        var matcher = GITHUB_REPO_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Repository URL must point to https://github.com/{owner}/{repo}");
        }
        String owner = matcher.group(1);
        String name = matcher.group(2);
        if (owner.equalsIgnoreCase("..") || name.equalsIgnoreCase("..")) {
            throw new IllegalArgumentException("Invalid repository path");
        }
        return new RepoRef(owner, name);
    }

    /**
     * Checks that every IP the hostname resolves to is publicly routable.
     *
     * <p>The resolution is skipped when an HTTP proxy is configured, because the proxy performs
     * the connection and the local resolver may not even know the target host.
     */
    public static void assertPublicHost(String hostname) {
        if (isProxyConfigured()) {
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new IllegalArgumentException(
                            "Host resolves to a non-public address: " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Cannot resolve host: " + hostname, ex);
        }
    }

    /**
     * Whether an HTTP proxy is configured for the JVM.
     */
    public static boolean isProxyConfigured() {
        String host = System.getProperty("http.proxyHost");
        return host != null && !host.isBlank();
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            // IPv6 is not used by any allowed host in V1, reject it defensively.
            return false;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        // 100.64.0.0/10 carrier grade NAT
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        // 192.0.0.0/24, 198.18.0.0/15 benchmarking, 198.51.100.0/24, 203.0.113.0/24
        if (first == 192 && second == 0) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19 || second == 51)) {
            return false;
        }
        if (first == 203 && second == 0) {
            return false;
        }
        // 240.0.0.0/4 reserved, 255.255.255.255 broadcast
        if (first >= 240) {
            return false;
        }
        return true;
    }
}
