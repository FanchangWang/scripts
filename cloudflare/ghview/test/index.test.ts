import { SELF } from "cloudflare:test";
import { afterEach, expect, it, vi } from "vite-plus/test";

afterEach(() => {
	vi.unstubAllGlobals();
});

function stubUpstream(handler: (url: URL, init: RequestInit) => Response) {
	const seen: URL[] = [];
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		const url =
			input instanceof URL ? input : new URL(typeof input === "string" ? input : input.url);
		seen.push(url);
		return handler(url, init ?? {});
	});
	return seen;
}

it("only allows GET and HEAD", async () => {
	const response = await SELF.fetch("https://example.com/gh/a/b/main/c", {
		method: "POST",
	});
	expect(response.status).toBe(405);
	expect(response.headers.get("Allow")).toBe("GET, HEAD");
});

it("serves the link generator page at the root", async () => {
	const response = await SELF.fetch("https://example.com/");
	expect(response.status).toBe(200);
	expect(response.headers.get("Content-Type")).toContain("text/html");
	const body = await response.text();
	expect(body).toContain("ghview 链接生成器");
	expect(body).toContain('id="source"');
	expect(body).toContain('id="generate"');
});

it("proxies /gh/ to raw.githubusercontent.com and scrubs response headers", async () => {
	const seen = stubUpstream(
		() =>
			new Response("hello", {
				headers: {
					"Content-Type": "text/plain",
					"Content-Security-Policy": "default-src 'none'",
					"Set-Cookie": "session=1",
					"Content-Disposition": "attachment",
				},
			}),
	);

	const response = await SELF.fetch("https://example.com/gh/octocat/hello-world/main/README.md");
	expect(response.status).toBe(200);
	expect(await response.text()).toBe("hello");
	expect(seen[0].href).toBe("https://raw.githubusercontent.com/octocat/hello-world/main/README.md");
	expect(response.headers.get("Content-Security-Policy")).toBeNull();
	expect(response.headers.get("Set-Cookie")).toBeNull();
	expect(response.headers.get("Content-Disposition")).toBeNull();
	expect(response.headers.get("X-Content-Type-Options")).toBe("nosniff");
});

it("maps /ghpages/ to the owner's github.io site", async () => {
	const seen = stubUpstream(() => new Response("page"));
	const response = await SELF.fetch("https://example.com/ghpages/owner/repo/a/b");
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://owner.github.io/repo/a/b");
});

it("accepts the full URL form and forwards the query string", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const response = await SELF.fetch(
		"https://example.com/https://raw.githubusercontent.com/a/b/main/f?raw=1",
	);
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://raw.githubusercontent.com/a/b/main/f?raw=1");
});

it("rejects hosts outside the allowlist and encoded traversal", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const badHost = await SELF.fetch("https://example.com/https://evil.example.com/a");
	expect(badHost.status).toBe(403);
	const traversal = await SELF.fetch("https://example.com/gh/owner/repo/main/%2e%2e/secret");
	// WHATWG URL normalizes %2e%2e before the Worker sees it; verify it cannot escape.
	expect(traversal.status).toBe(200);
	expect(seen).toHaveLength(1);
	expect(seen[0].pathname).toBe("/owner/repo/secret");
});

it("forces HTML MIME for raw HTML and marks upstream errors no-store", async () => {
	stubUpstream(
		() =>
			new Response("<h1>x</h1>", {
				status: 404,
				headers: { "Content-Type": "application/octet-stream" },
			}),
	);
	const response = await SELF.fetch("https://example.com/gh/owner/repo/main/index.html");
	expect(response.status).toBe(404);
	expect(response.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
	expect(response.headers.get("Cache-Control")).toBe("no-store");
});

it("rewrites root-relative subresources using the document Referer", async () => {
	const seen = stubUpstream(() => new Response("body"));
	const response = await SELF.fetch("https://example.com/styles/index.css", {
		headers: {
			Referer: "https://example.com/gh/owner/repo/main/index.html",
		},
	});
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://raw.githubusercontent.com/owner/repo/main/styles/index.css");
});

it("resolves root-relative resources for Full URL documents too", async () => {
	const seen = stubUpstream(() => new Response("body"));
	const response = await SELF.fetch("https://example.com/assets/app.js", {
		headers: {
			Referer: "https://example.com/https://owner.github.io/repo/index.html",
		},
	});
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://owner.github.io/repo/assets/app.js");
});

it("fixes text/plain MIME for raw CSS and JavaScript", async () => {
	stubUpstream(
		() =>
			new Response("body{}", {
				headers: { "Content-Type": "text/plain; charset=utf-8" },
			}),
	);
	const css = await SELF.fetch("https://example.com/gh/owner/repo/main/a.css");
	expect(css.headers.get("Content-Type")).toBe("text/css; charset=utf-8");
	const js = await SELF.fetch("https://example.com/gh/owner/repo/main/a.js");
	expect(js.headers.get("Content-Type")).toBe("text/javascript; charset=utf-8");
});

it("returns 502 when the upstream fetch fails", async () => {
	vi.stubGlobal("fetch", async () => {
		throw new Error("network down");
	});
	const response = await SELF.fetch("https://example.com/gh/owner/repo/main/f");
	expect(response.status).toBe(502);
	expect(await response.text()).toBe("Upstream request failed");
});
