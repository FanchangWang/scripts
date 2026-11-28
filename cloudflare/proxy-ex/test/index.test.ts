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

it("serves the link generator page at the root", async () => {
	const response = await SELF.fetch("https://example.com/");
	expect(response.status).toBe(200);
	const body = await response.text();
	expect(body).toContain("通用代理链接生成器");
	expect(body).toContain('id="source"');
});

it("proxies an arbitrary full URL", async () => {
	const seen = stubUpstream(() => new Response("hello"));
	const response = await SELF.fetch("https://example.com/https://example.org/a/b?x=1");
	expect(response.status).toBe(200);
	expect(await response.text()).toBe("hello");
	expect(seen[0].href).toBe("https://example.org/a/b?x=1");
});

it("accepts the scheme-less host form", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const response = await SELF.fetch("https://example.com/example.org/a/b");
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://example.org/a/b");
});

it("redirects the legacy q parameter", async () => {
	const response = await SELF.fetch("https://example.com/?q=https://example.org/a", {
		redirect: "manual",
	});
	expect(response.status).toBe(301);
	expect(response.headers.get("Location")).toBe("https://example.com/https://example.org/a");
});

it("requires a local Referer for mutating methods", async () => {
	const response = await SELF.fetch("https://example.com/https://example.org/a", {
		method: "POST",
		body: "x",
	});
	expect(response.status).toBe(403);
});

it("forwards POST bodies from proxied pages", async () => {
	let seenMethod = "";
	let seenBody = "";
	vi.stubGlobal("fetch", async (_input: RequestInfo | URL, init?: RequestInit) => {
		seenMethod = init?.method ?? "GET";
		if (init?.body) {
			seenBody = await new Response(init.body as BodyInit).text();
		}
		return new Response('{"ok":true}', {
			headers: { "Content-Type": "application/json" },
		});
	});
	const response = await SELF.fetch("https://example.com/https://example.org/api", {
		method: "POST",
		headers: { Referer: "https://example.com/https://example.org/page" },
		body: '{"a":1}',
	});
	expect(response.status).toBe(200);
	expect(seenMethod).toBe("POST");
	expect(seenBody).toBe('{"a":1}');
});

it("allows other mutating methods with a local Referer", async () => {
	const seen: string[] = [];
	vi.stubGlobal("fetch", async (_input: RequestInfo | URL, init?: RequestInit) => {
		seen.push(init?.method ?? "GET");
		return new Response("ok");
	});
	const response = await SELF.fetch("https://example.com/https://example.org/api", {
		method: "PUT",
		headers: { Referer: "https://example.com/https://example.org/page" },
		body: "data",
	});
	expect(response.status).toBe(200);
	expect(seen).toEqual(["PUT"]);
});

it("does not mistake static file names for hostnames", async () => {
	const seen = stubUpstream(() => new Response("js"));
	const response = await SELF.fetch("https://example.com/site-escape.js?v=20261127005", {
		headers: { Referer: "https://example.com/https://huangguoai.com/video/16325/" },
	});
	expect(response.status).toBe(200);
	expect(await response.text()).toBe("js");
	expect(seen[0].href).toBe("https://huangguoai.com/site-escape.js?v=20261127005");
});

it("serves bare POST API paths in place without redirecting", async () => {
	let seenMethod = "";
	let seenUrl = "";
	let seenBody = "";
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		const url =
			input instanceof URL ? input : new URL(typeof input === "string" ? input : input.url);
		seenUrl = url.href;
		seenMethod = init?.method ?? "GET";
		if (init?.body) seenBody = await new Response(init.body as BodyInit).text();
		return new Response('{"ok":true}');
	});
	const response = await SELF.fetch("https://example.com/api/playback/request", {
		method: "POST",
		headers: {
			Referer: "https://example.com/https://example.org/page",
			"Content-Type": "application/json",
		},
		body: '{"type":"hello"}',
	});
	expect(response.status).toBe(200);
	expect(seenMethod).toBe("POST");
	expect(seenBody).toBe('{"type":"hello"}');
	expect(seenUrl).toBe("https://example.org/api/playback/request");
});

it("answers OPTIONS preflight locally", async () => {
	const seen = stubUpstream(() => new Response("unexpected"));
	const response = await SELF.fetch("https://example.com/https://example.org/api", {
		method: "OPTIONS",
		headers: { "Access-Control-Request-Headers": "authorization,content-type" },
	});
	expect(response.status).toBe(204);
	expect(response.headers.get("Access-Control-Allow-Methods")).toContain("POST");
	expect(response.headers.get("Access-Control-Allow-Headers")).toBe("authorization,content-type");
	expect(seen).toHaveLength(0);
});

it("blocks private addresses, userinfo, non-http schemes and odd ports", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	expect((await SELF.fetch("https://example.com/https://127.0.0.1/a")).status).toBe(403);
	expect((await SELF.fetch("https://example.com/https://u:p@example.org/a")).status).toBe(403);
	expect((await SELF.fetch("https://example.com/ftp://example.org/a")).status).toBe(400);
	expect((await SELF.fetch("https://example.com/https://example.org:8080/a")).status).toBe(403);
	expect(seen).toHaveLength(0);
});

it("serves root-relative subresources in place from Referer", async () => {
	const seen = stubUpstream(() => new Response("body"));
	const response = await SELF.fetch("https://example.com/styles/index.css", {
		headers: {
			Referer: "https://example.com/https://example.org/docs/index.html",
		},
	});
	expect(response.status).toBe(200);
	expect(await response.text()).toBe("body");
	expect(seen[0].href).toBe("https://example.org/styles/index.css");
});

it("redirects bare document paths to the canonical proxy URL", async () => {
	stubUpstream(() => new Response("html"));
	const response = await SELF.fetch("https://example.com/video/16325/", {
		headers: {
			Referer: "https://example.com/https://example.org/",
			Accept: "text/html",
		},
		redirect: "manual",
	});
	expect(response.status).toBe(302);
	expect(response.headers.get("Location")).toBe(
		"https://example.com/https://example.org/video/16325/",
	);
});

it("passes through Content-Type and strips only CSP and Set-Cookie", async () => {
	stubUpstream(
		() =>
			new Response("body{}", {
				headers: {
					"Content-Type": "text/plain",
					"Content-Security-Policy": "default-src 'none'",
					"Set-Cookie": "session=1",
					"X-Custom": "keep-me",
				},
			}),
	);
	const response = await SELF.fetch("https://example.com/https://example.org/a.css");
	expect(response.headers.get("Content-Type")).toBe("text/plain");
	expect(response.headers.get("Cache-Control")).toBe("no-store");
	expect(response.headers.get("Content-Security-Policy")).toBeNull();
	expect(response.headers.get("Set-Cookie")).toBeNull();
	expect(response.headers.get("X-Custom")).toBe("keep-me");
	expect(response.headers.get("X-Content-Type-Options")).toBeNull();
});

it("supports HEAD requests", async () => {
	stubUpstream(() => new Response("ignored"));
	const response = await SELF.fetch("https://example.com/https://example.org/a", {
		method: "HEAD",
	});
	expect(response.status).toBe(200);
});
