import { SELF } from "cloudflare:test";
import { afterEach, expect, it, vi } from "vite-plus/test";

afterEach(() => {
	vi.unstubAllGlobals();
});

function stubUpstream(handler: (url: URL, init: RequestInit) => Response): URL[] {
	const seen: URL[] = [];
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		const url =
			input instanceof URL ? input : new URL(typeof input === "string" ? input : input.url);
		seen.push(url);
		return handler(url, init ?? {});
	});
	return seen;
}

it("serves the usage page at the root", async () => {
	const response = await SELF.fetch("https://example.com/");
	expect(response.status).toBe(200);
	expect(await response.text()).toContain("Docker 镜像加速代理");
});

it("rewrites the auth realm on the version check", async () => {
	const seen = stubUpstream(
		() =>
			new Response(null, {
				status: 401,
				headers: {
					"WWW-Authenticate":
						'Bearer realm="https://auth.docker.io/token",service="registry.docker.io"',
				},
			}),
	);
	const response = await SELF.fetch("https://example.com/v2/");
	expect(seen[0].href).toBe("https://registry-1.docker.io/v2/");
	expect(response.headers.get("WWW-Authenticate")).toContain("https://example.com/token");
});

it("does not rewrite the realm for non-DockerHub registries", async () => {
	stubUpstream(
		() =>
			new Response(null, {
				status: 401,
				headers: {
					"WWW-Authenticate": 'Bearer realm="https://quay.io/v2/auth"',
				},
			}),
	);
	const response = await SELF.fetch("https://example.com/v2/?ns=quay.io");
	expect(response.headers.get("WWW-Authenticate")).toBe('Bearer realm="https://quay.io/v2/auth"');
});

it("sends Basic auth with the PAT on /token", async () => {
	const seen = stubUpstream(() => new Response('{"token":"x"}'));
	const response = await SELF.fetch(
		"https://example.com/token?service=registry.docker.io&scope=repository:library/alpine:pull",
	);
	expect(response.status).toBe(200);
	expect(seen[0].hostname).toBe("auth.docker.io");
	expect(seen[0].searchParams.get("scope")).toBe("repository:library/alpine:pull");
});

it("normalizes Docker Hub shorthand to library/", async () => {
	const seen = stubUpstream(() => new Response("{}"));
	await SELF.fetch("https://example.com/v2/nginx/manifests/latest");
	expect(seen[0].pathname).toBe("/v2/library/nginx/manifests/latest");
});

it("proxies manifest and blob requests directly", async () => {
	const digest = `sha256:${"a".repeat(64)}`;
	const seen = stubUpstream(() => new Response("data"));
	const manifest = await SELF.fetch("https://example.com/v2/acme/manifests/latest");
	const blob = await SELF.fetch(`https://example.com/v2/acme/blobs/${digest}`);
	expect(manifest.status).toBe(200);
	expect(blob.status).toBe(200);
	expect(seen.map((url) => url.pathname)).toEqual([
		"/v2/library/acme/manifests/latest",
		`/v2/library/acme/blobs/${digest}`,
	]);
	expect(seen[0].hostname).toBe("registry-1.docker.io");
});

it("passes request method and body through", async () => {
	const seen: RequestInit[] = [];
	vi.stubGlobal("fetch", async (_input: RequestInfo | URL, init?: RequestInit) => {
		seen.push(init ?? {});
		return new Response("ok");
	});
	const response = await SELF.fetch("https://example.com/v2/acme/blobs/upload", {
		method: "POST",
		body: "payload",
	});
	expect(response.status).toBe(200);
	expect(seen[0].method).toBe("POST");
});

it("searches Docker Hub repositories with an authenticated API call", async () => {
	const seen = stubUpstream((url) =>
		url.pathname === "/v2/users/login/"
			? Response.json({ token: "test-hub-token" })
			: Response.json({
					count: 2,
					results: [
						{
							repo_name: "library/nginx",
							short_description: "Official Nginx",
							star_count: 10,
							pull_count: 99,
							is_official: true,
						},
						{ repo_name: "acme/nginx-proxy" },
					],
				}),
	);
	const response = await SELF.fetch(
		"https://example.com/search?q=nginx%20proxy&page=1&page_size=20",
	);
	expect(response.status).toBe(200);
	const data = (await response.json()) as { count: number; results: { name: string }[] };
	expect(data.count).toBe(2);
	expect(data.results.map((item) => item.name)).toEqual(["library/nginx", "acme/nginx-proxy"]);
	const searchUrl = seen.find((url) => url.pathname === "/v2/search/repositories/");
	expect(searchUrl?.hostname).toBe("hub.docker.com");
	expect(searchUrl?.searchParams.get("query")).toBe("nginx proxy");
	expect(searchUrl?.searchParams.get("page_size")).toBe("20");
});

it("lists tags with a name filter for the requested repo", async () => {
	const seen = stubUpstream((url) =>
		url.pathname === "/v2/users/login/"
			? Response.json({ token: "test-hub-token" })
			: Response.json({
					count: 1,
					results: [{ name: "1.27-alpine", digest: "sha256:abc", last_updated: "2026-01-01" }],
				}),
	);
	const response = await SELF.fetch(
		"https://example.com/tags?repo=library/nginx&name=1.27&page=2&page_size=20",
	);
	expect(response.status).toBe(200);
	const data = (await response.json()) as {
		count: number;
		results: { name: string; digest: string }[];
	};
	expect(data.results[0].name).toBe("1.27-alpine");
	const tagsUrl = seen.find((url) => url.pathname.endsWith("/tags/"));
	expect(tagsUrl?.pathname).toBe("/v2/namespaces/library/repositories/nginx/tags/");
	expect(tagsUrl?.searchParams.get("name")).toBe("1.27");
	expect(tagsUrl?.searchParams.get("page")).toBe("2");
});

it("rejects invalid tag repository names", async () => {
	const response = await SELF.fetch("https://example.com/tags?repo=bad/name/extra");
	expect(response.status).toBe(400);
});
