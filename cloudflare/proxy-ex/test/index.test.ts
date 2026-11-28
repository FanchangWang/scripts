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

it("allows only GET and HEAD", async () => {
	const response = await SELF.fetch("https://example.com/https://example.org/a", {
		method: "POST",
		body: "x",
	});
	expect(response.status).toBe(405);
	expect(response.headers.get("Allow")).toBe("GET, HEAD");
});

it("blocks private addresses, userinfo, non-http schemes and odd ports", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	expect((await SELF.fetch("https://example.com/https://127.0.0.1/a")).status).toBe(403);
	expect((await SELF.fetch("https://example.com/https://u:p@example.org/a")).status).toBe(403);
	expect((await SELF.fetch("https://example.com/ftp://example.org/a")).status).toBe(400);
	expect((await SELF.fetch("https://example.com/https://example.org:8080/a")).status).toBe(403);
	expect(seen).toHaveLength(0);
});

it("redirects root-relative subresources to the canonical proxy URL", async () => {
	const seen = stubUpstream(() => new Response("body"));
	const response = await SELF.fetch("https://example.com/styles/index.css", {
		headers: {
			Referer: "https://example.com/https://example.org/docs/index.html",
		},
		redirect: "manual",
	});
	expect(response.status).toBe(302);
	const location = response.headers.get("Location");
	expect(location).toBe("https://example.com/https://example.org/styles/index.css");

	const followed = await SELF.fetch(location!);
	expect(followed.status).toBe(200);
	expect(seen[0].href).toBe("https://example.org/styles/index.css");
});

it("redirects bare document paths to the canonical proxy URL", async () => {
	stubUpstream(() => new Response("html"));
	const response = await SELF.fetch("https://example.com/video/16325/", {
		headers: { Referer: "https://example.com/https://example.org/" },
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
