import { SELF } from "cloudflare:test";
import { afterEach, expect, it, vi } from "vite-plus/test";

afterEach(() => {
	vi.unstubAllGlobals();
});

function stubUpstream(handler: (url: URL) => Response): URL[] {
	const seen: URL[] = [];
	vi.stubGlobal("fetch", async (input: RequestInfo | URL) => {
		const url =
			input instanceof URL ? input : new URL(typeof input === "string" ? input : input.url);
		seen.push(url);
		return handler(url);
	});
	return seen;
}

it("serves the link generator page with both modes", async () => {
	const response = await SELF.fetch("https://example.com/");
	expect(response.status).toBe(200);
	const body = await response.text();
	expect(body).toContain("github 代理链接生成器");
	expect(body).toContain('id="mode-normal"');
	expect(body).toContain('id="mode-deep"');
});

it("proxies a full URL unchanged", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const response = await SELF.fetch(
		"https://example.com/https://raw.githubusercontent.com/o/r/main/f?x=1",
	);
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://raw.githubusercontent.com/o/r/main/f?x=1");
});

it("accepts the scheme-less host form", async () => {
	const seen = stubUpstream(() => new Response("zip"));
	const response = await SELF.fetch(
		"https://example.com/github.com/o/r/releases/download/v1/app.zip",
	);
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://github.com/o/r/releases/download/v1/app.zip");
});

it("maps raw and blob shorthands to raw.githubusercontent.com", async () => {
	const seen = stubUpstream(() => new Response("file"));
	await SELF.fetch("https://example.com/o/r/master/README");
	expect(seen[0].href).toBe("https://raw.githubusercontent.com/o/r/master/README");
	await SELF.fetch("https://example.com/o/r/blob/master/a.sh");
	expect(seen[1].href).toBe("https://raw.githubusercontent.com/o/r/master/a.sh");
});

it("keeps release and archive shorthands on github.com", async () => {
	const seen = stubUpstream(() => new Response("bin"));
	await SELF.fetch("https://example.com/o/r/releases/download/v1/a.zip");
	await SELF.fetch("https://example.com/o/r/archive/refs/tags/v1.zip");
	expect(seen[0].hostname).toBe("github.com");
	expect(seen[1].hostname).toBe("github.com");
});

it("supports api, gist and git clone shorthands", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	await SELF.fetch("https://example.com/api.github.com/repos/o/r");
	await SELF.fetch("https://example.com/gist/o/id/raw/a.cs");
	await SELF.fetch("https://example.com/o/r.git");
	expect(seen[0].href).toBe("https://api.github.com/repos/o/r");
	expect(seen[1].href).toBe("https://gist.githubusercontent.com/o/id/raw/a.cs");
	expect(seen[2].href).toBe("https://github.com/o/r.git");
});

it("blocks private addresses and userinfo", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const privateIp = await SELF.fetch("https://example.com/https://127.0.0.1/a");
	expect(privateIp.status).toBe(403);
	const userinfo = await SELF.fetch("https://example.com/https://u:p@github.com/a");
	expect(userinfo.status).toBe(403);
	expect(seen).toHaveLength(0);
});

it("redirects the legacy q parameter", async () => {
	const response = await SELF.fetch("https://example.com/?q=https://github.com/o/r", {
		redirect: "manual",
	});
	expect(response.status).toBe(301);
	expect(response.headers.get("Location")).toBe("https://example.com/https://github.com/o/r");
});

it("allows POST only for GitHub targets", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const allowed = await SELF.fetch("https://example.com/o/r/git-upload-pack", {
		method: "POST",
		body: "payload",
	});
	expect(allowed.status).toBe(200);
	const blocked = await SELF.fetch("https://example.com/o/r/main/f", {
		method: "POST",
		body: "payload",
	});
	expect(blocked.status).toBe(405);
	expect(seen).toHaveLength(1);
});

it("rewrites GitHub links in text and JSON in deep mode", async () => {
	const body = JSON.stringify({
		url: "https://api.github.com/repos/o/r/releases/latest",
		asset: "https://github.com/o/r/releases/download/v1/a.zip",
	});
	stubUpstream(
		() =>
			new Response(body, {
				headers: { "Content-Type": "application/json" },
			}),
	);
	const response = await SELF.fetch(
		"https://example.com/deep/https://raw.githubusercontent.com/o/r/main/install.json",
	);
	const text = await response.text();
	expect(response.headers.get("X-Deep-Convert")).toBe("applied");
	expect(text).toContain(
		"https://example.com/deep/https://api.github.com/repos/o/r/releases/latest",
	);
	expect(text).toContain(
		"https://example.com/deep/https://github.com/o/r/releases/download/v1/a.zip",
	);
	expect(text).not.toContain('"url":"https://api.github.com');
});

it("does not double-rewrite links already using the proxy", async () => {
	const original = "https://example.com/deep/https://github.com/o/r/releases/download/v1/a.zip";
	stubUpstream(
		() =>
			new Response(original, {
				headers: { "Content-Type": "text/plain" },
			}),
	);
	const response = await SELF.fetch(
		"https://example.com/deep/https://raw.githubusercontent.com/o/r/main/a.ps1",
	);
	expect(await response.text()).toBe(original);
});

it("skips deep conversion for binary responses", async () => {
	stubUpstream(
		() =>
			new Response("binary", {
				headers: { "Content-Type": "application/octet-stream" },
			}),
	);
	const response = await SELF.fetch(
		"https://example.com/deep/https://github.com/o/r/releases/download/v1/a.zip",
	);
	expect(response.headers.get("X-Deep-Convert")).toBe("skipped");
	expect(await response.text()).toBe("binary");
});

it("blocks hosts outside the GitHub ecosystem", async () => {
	const seen = stubUpstream(() => new Response("ok"));
	const response = await SELF.fetch("https://example.com/https://www.baidu.com/a");
	expect(response.status).toBe(403);
	expect(seen).toHaveLength(0);
});

it("allows github.io full URLs", async () => {
	const seen = stubUpstream(() => new Response("page"));
	const response = await SELF.fetch("https://example.com/https://owner.github.io/repo/index.html");
	expect(response.status).toBe(200);
	expect(seen[0].href).toBe("https://owner.github.io/repo/index.html");
});

it("passes through the upstream Content-Type untouched", async () => {
	stubUpstream(
		() =>
			new Response("body{}", {
				headers: { "Content-Type": "text/plain" },
			}),
	);
	const css = await SELF.fetch("https://example.com/o/r/main/a.css");
	expect(css.headers.get("Content-Type")).toBe("text/plain");
	const html = await SELF.fetch("https://example.com/o/r/main/a.html");
	expect(html.headers.get("Content-Type")).toBe("text/plain");
});
