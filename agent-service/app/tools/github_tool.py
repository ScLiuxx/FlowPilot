"""GitHub REST API tools.

Security rules implemented here (protocol design document, section 12.3):

* **Allow list** — only the five read operations of a single repository are
  exposed (``repository``, ``commits``, ``issues``, ``pull requests``,
  ``releases``). No arbitrary URL fetching, no shell execution, no local file
  access.
* **SSRF defence** — every request goes to the fixed base URL
  ``https://api.github.com``. The host is *never* derived from user input and
  redirects are not followed, so a malicious repository cannot make the agent
  service talk to an internal address or a cloud metadata endpoint.
* **Input validation** — the repository reference must be ``owner/repo`` or
  ``https://github.com/owner/repo``; anything else is rejected before a
  request is built. The validated segments cannot contain ``/`` or ``..``, so
  no path traversal is possible.
* **Untrusted content** — repository content is returned raw but sanitised by
  :func:`app.tools.untrusted.sanitize_untrusted` and must be embedded into a
  prompt through :func:`app.tools.untrusted.untrusted_block`.
"""

from __future__ import annotations

import base64
import re
from typing import Any, Awaitable, Callable

from opentelemetry.trace import Status, StatusCode

from app.runtime.errors import (
    PermissionError_,
    RateLimitError,
    TransientError,
    ValidationError,
)
from app.telemetry import get_tracer
from app.tools.untrusted import sanitize_untrusted

#: Fixed API base URL. Never configurable, never derived from user input.
GITHUB_API_BASE_URL = "https://api.github.com"

_OWNER_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38})$")
_REPO_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})$")
_GITHUB_URL_PREFIX = "https://github.com/"


def normalize_repository_ref(ref: str | None) -> str:
    """Validate and normalise a repository reference to ``owner/repo``.

    Accepted input:

    * ``owner/repo``
    * ``https://github.com/owner/repo`` (optional trailing ``/`` or ``.git``)

    Everything else — other hosts, other schemes, ``git@`` remotes, URLs with
    additional path segments, query strings, user info — is rejected.
    """
    if ref is None or not isinstance(ref, str):
        raise ValidationError(
            "repository reference must be a string",
            error_code="GITHUB_INVALID_REPOSITORY",
        )
    candidate = ref.strip()
    if not candidate:
        raise ValidationError(
            "repository reference must not be empty",
            error_code="GITHUB_INVALID_REPOSITORY",
        )
    if candidate.startswith(_GITHUB_URL_PREFIX):
        candidate = candidate[len(_GITHUB_URL_PREFIX) :]
    elif "://" in candidate or candidate.startswith("git@") or "@" in candidate:
        raise ValidationError(
            "only 'owner/repo' or 'https://github.com/owner/repo' are accepted",
            error_code="GITHUB_INVALID_REPOSITORY",
            diagnostics={"repository": ref},
        )

    candidate = candidate.strip("/")
    if candidate.endswith(".git"):
        candidate = candidate[: -len(".git")]
    segments = candidate.split("/")
    if len(segments) != 2 or not all(segments):
        raise ValidationError(
            "only 'owner/repo' or 'https://github.com/owner/repo' are accepted",
            error_code="GITHUB_INVALID_REPOSITORY",
            diagnostics={"repository": ref},
        )
    owner, repo = segments
    if not _OWNER_RE.match(owner) or not _REPO_RE.match(repo):
        raise ValidationError(
            "repository reference contains unsupported characters",
            error_code="GITHUB_INVALID_REPOSITORY",
            diagnostics={"repository": ref},
        )
    return f"{owner}/{repo}"


class GitHubTool:
    """Read only GitHub client bound to a single repository per call."""

    def __init__(
        self,
        *,
        token: str | None = None,
        timeout: float = 20.0,
        max_items: int = 20,
        max_content_chars: int = 4000,
        client: Any = None,
    ) -> None:
        self._token = token
        self._timeout = timeout
        self._max_items = max_items
        self._max_content_chars = max_content_chars
        self._client = client
        self._owns_client = client is None

    # -- plumbing --------------------------------------------------------
    async def _get_client(self):
        if self._client is None:
            import httpx

            self._client = httpx.AsyncClient(
                timeout=self._timeout,
                follow_redirects=False,
                headers={"User-Agent": "flowpilot-agent-service"},
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    def _headers(self) -> dict[str, str]:
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        return headers

    async def _request(self, path: str, params: dict[str, Any] | None = None) -> Any:
        url = f"{GITHUB_API_BASE_URL}{path}"
        client = await self._get_client()
        try:
            response = await client.get(url, params=params, headers=self._headers())
        except Exception as exc:  # httpx.TransportError and friends
            raise TransientError(
                f"GitHub API request failed: {type(exc).__name__}",
                error_code="GITHUB_API_UNAVAILABLE",
                diagnostics={"error": str(exc)},
            ) from exc
        return self._handle_response(response)

    def _handle_response(self, response: Any) -> Any:
        status = response.status_code
        headers = {k.lower(): v for k, v in response.headers.items()}

        if status == 429:
            raise RateLimitError(
                "GitHub API rate limit exceeded",
                error_code="GITHUB_RATE_LIMITED",
                diagnostics={
                    "retryAfter": headers.get("retry-after"),
                    "limit": headers.get("x-ratelimit-limit"),
                },
            )
        if status in (301, 302, 307, 308):
            # Redirects are never followed: the location could point anywhere.
            raise ValidationError(
                "repository was moved or renamed",
                error_code="GITHUB_REPO_MOVED",
            )
        if status == 401:
            raise PermissionError_(
                "GitHub authentication failed",
                error_code="GITHUB_AUTH_FAILED",
            )
        if status == 403:
            remaining = headers.get("x-ratelimit-remaining")
            if remaining == "0" or "retry-after" in headers:
                raise RateLimitError(
                    "GitHub API rate limit exceeded",
                    error_code="GITHUB_RATE_LIMITED",
                    diagnostics={"retryAfter": headers.get("retry-after")},
                )
            raise PermissionError_(
                "GitHub API denied the request",
                error_code="GITHUB_FORBIDDEN",
            )
        if status == 404:
            raise ValidationError(
                "repository or resource not found",
                error_code="GITHUB_REPO_NOT_FOUND",
            )
        if status >= 500:
            raise TransientError(
                f"GitHub API returned HTTP {status}",
                error_code="GITHUB_API_UNAVAILABLE",
                diagnostics={"status": status},
            )
        if status >= 400:
            raise ValidationError(
                f"GitHub API returned HTTP {status}",
                error_code="GITHUB_API_ERROR",
                diagnostics={"status": status},
            )
        try:
            return response.json()
        except Exception as exc:
            raise TransientError(
                "GitHub API returned a malformed response",
                error_code="GITHUB_API_MALFORMED",
            ) from exc

    # -- allow listed operations -----------------------------------------
    async def _operation(
        self,
        name: str,
        repository: str,
        call: Callable[[], Awaitable[Any]],
    ) -> Any:
        """Run one allow listed operation inside its own span."""
        tracer = get_tracer()
        with tracer.start_as_current_span(
            f"tool.github_{name}", attributes={"repository": repository}
        ) as span:
            try:
                return await call()
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(
                    Status(
                        StatusCode.ERROR,
                        getattr(exc, "error_code", None) or type(exc).__name__,
                    )
                )
                raise

    async def get_repository(self, ref: str) -> dict[str, Any]:
        """Public metadata of the repository."""
        owner_repo = normalize_repository_ref(ref)
        data = await self._operation(
            "get_repository",
            owner_repo,
            lambda: self._request(f"/repos/{owner_repo}"),
        )
        if not isinstance(data, dict):
            raise TransientError(
                "GitHub API returned an unexpected payload",
                error_code="GITHUB_API_MALFORMED",
            )
        return {
            "fullName": data.get("full_name"),
            "description": sanitize_untrusted(
                data.get("description"), max_chars=self._max_content_chars
            ),
            "stars": data.get("stargazers_count"),
            "forks": data.get("forks_count"),
            "openIssues": data.get("open_issues_count"),
            "watchers": data.get("watchers_count"),
            "language": data.get("language"),
            "license": (data.get("license") or {}).get("spdx_id"),
            "defaultBranch": data.get("default_branch"),
            "createdAt": data.get("created_at"),
            "updatedAt": data.get("updated_at"),
            "pushedAt": data.get("pushed_at"),
            "archived": data.get("archived"),
            "topics": data.get("topics") or [],
        }

    async def list_commits(self, ref: str, per_page: int = 10) -> list[dict[str, Any]]:
        owner_repo = normalize_repository_ref(ref)
        data = await self._operation(
            "list_commits",
            owner_repo,
            lambda: self._request(
                f"/repos/{owner_repo}/commits",
                params={"per_page": self._limit(per_page)},
            ),
        )
        if not isinstance(data, list):
            return []
        commits = []
        for item in data[: self._max_items]:
            commit = item.get("commit") or {}
            author = commit.get("author") or {}
            commits.append(
                {
                    "sha": (item.get("sha") or "")[:12],
                    "message": sanitize_untrusted(
                        commit.get("message"), max_chars=500
                    ),
                    "author": sanitize_untrusted(author.get("name"), max_chars=120),
                    "date": author.get("date"),
                }
            )
        return commits

    async def list_issues(self, ref: str, state: str = "open", per_page: int = 10) -> list[dict[str, Any]]:
        """The issues of a repository.

        The GitHub endpoint answers with the open issues by default and mixes
        the pull requests into the result. The state is therefore part of the
        call, so the closed issues can be collected separately.
        """
        owner_repo = normalize_repository_ref(ref)
        data = await self._operation(
            "list_issues",
            owner_repo,
            lambda: self._request(
                f"/repos/{owner_repo}/issues",
                params={"state": state, "per_page": self._limit(per_page)},
            ),
        )
        if not isinstance(data, list):
            return []
        issues = []
        for item in data[: self._max_items]:
            issues.append(
                {
                    "number": item.get("number"),
                    "title": sanitize_untrusted(item.get("title"), max_chars=300),
                    "body": sanitize_untrusted(
                        item.get("body"), max_chars=self._max_content_chars
                    ),
                    "state": item.get("state"),
                    "comments": item.get("comments"),
                    "createdAt": item.get("created_at"),
                    "user": ((item.get("user") or {}).get("login")),
                }
            )
        return issues

    async def list_closed_issues(self, ref: str, per_page: int = 10) -> list[dict[str, Any]]:
        """The closed issues of a repository.

        The issues endpoint only answers with the open issues by default, so the
        closed ones have to be asked for separately.
        """
        return await self.list_issues(ref, state="closed", per_page=per_page)

    async def list_pull_requests(self, ref: str, state: str = "open", per_page: int = 10) -> list[dict[str, Any]]:
        owner_repo = normalize_repository_ref(ref)
        data = await self._operation(
            "list_pull_requests",
            owner_repo,
            lambda: self._request(
                f"/repos/{owner_repo}/pulls",
                params={"state": state, "per_page": self._limit(per_page)},
            ),
        )
        if not isinstance(data, list):
            return []
        pulls = []
        for item in data[: self._max_items]:
            pulls.append(
                {
                    "number": item.get("number"),
                    "title": sanitize_untrusted(item.get("title"), max_chars=300),
                    "state": item.get("state"),
                    "draft": item.get("draft"),
                    "createdAt": item.get("created_at"),
                    "user": ((item.get("user") or {}).get("login")),
                }
            )
        return pulls

    async def list_releases(self, ref: str, per_page: int = 5) -> list[dict[str, Any]]:
        owner_repo = normalize_repository_ref(ref)
        data = await self._operation(
            "list_releases",
            owner_repo,
            lambda: self._request(
                f"/repos/{owner_repo}/releases",
                params={"per_page": self._limit(per_page)},
            ),
        )
        if not isinstance(data, list):
            return []
        releases = []
        for item in data[: self._max_items]:
            releases.append(
                {
                    "tag": item.get("tag_name"),
                    "name": sanitize_untrusted(item.get("name"), max_chars=200),
                    "publishedAt": item.get("published_at"),
                    "prerelease": item.get("prerelease"),
                }
            )
        return releases

    async def get_readme(self, ref: str) -> str:
        """Raw README text (already sanitised and truncated)."""
        owner_repo = normalize_repository_ref(ref)
        try:
            data = await self._operation(
                "get_readme",
                owner_repo,
                lambda: self._request(f"/repos/{owner_repo}/readme"),
            )
        except ValidationError as exc:
            if exc.error_code == "GITHUB_REPO_NOT_FOUND":
                return ""
            raise
        if not isinstance(data, dict):
            return ""
        encoded = data.get("content") or ""
        try:
            raw = base64.b64decode(encoded).decode("utf-8", errors="replace")
        except Exception:  # pragma: no cover - defensive
            return ""
        return sanitize_untrusted(raw, max_chars=self._max_content_chars)

    def _limit(self, per_page: int) -> int:
        return max(1, min(int(per_page), self._max_items))
