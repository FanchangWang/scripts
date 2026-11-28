import { rewriteText, isTextual, MAX_DEEP_BYTES } from "./deep.ts";
import { isGitHubHost, isGitHubPostAllowed, resolveProxyTarget, toProxyPath } from "./formats.ts";
import { PAGE_HTML } from "./page.ts";

const CACHE_TTL_SECONDS = 300;

function textResponse(
	message: string,
	status: number,
	extra: Record<string, string> = {},
): Response {
	return new Response(message, {
		status,
		headers: {
			"Content-Type": "text/plain; charset=utf-8",
			"Cache-Control": "no-store",
			...extra,
		},
	});
}

function outboundHeaders(request: Request, deep: boolean): Headers {
	const headers = new Headers();
	const stripped = new Set([
		"host",
		"connection",
		"proxy-connection",
		"keep-alive",
		"transfer-encoding",
		"upgrade",
		"cf-connecting-ip",
		"cf-ipcountry",
		"cf-ray",
		"cookie",
		"referer",
		"origin",
	]);
	for (const [key, value] of request.headers.entries()) {
		if (!stripped.has(key.toLowerCase())) headers.set(key, value);
	}
	if (deep) headers.set("Accept-Encoding", "identity");
	return headers;
}

function responseHeaders(upstream: Response): Headers {
	const headers = new Headers(upstream.headers);
	headers.delete("Content-Security-Policy");
	headers.delete("Content-Security-Policy-Report-Only");
	headers.delete("Set-Cookie");
	headers.set("X-Content-Type-Options", "nosniff");
	headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
	return headers;
}

async function deepResponse(upstream: Response, targetUrl: URL, origin: string): Promise<Response> {
	const headers = responseHeaders(upstream);
	const contentType = upstream.headers.get("Content-Type") ?? "";
	const encoded = upstream.headers.has("Content-Encoding");

	if (encoded || !isTextual(contentType, targetUrl.pathname)) {
		headers.set("X-Deep-Convert", "skipped");
		return new Response(upstream.body, { status: upstream.status, headers });
	}

	const original = upstream.clone();
	const text = await upstream.text();
	if (new TextEncoder().encode(text).length > MAX_DEEP_BYTES) {
		headers.set("X-Deep-Convert", "skipped");
		return new Response(original.body, { status: upstream.status, headers });
	}

	headers.delete("Content-Length");
	headers.delete("ETag");
	headers.set("X-Deep-Convert", "applied");
	return new Response(rewriteText(text, origin), {
		status: upstream.status,
		headers,
	});
}

export default {
	async fetch(request) {
		const method = request.method;
		if (method !== "GET" && method !== "HEAD" && method !== "POST") {
			return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD, POST" });
		}

		const incoming = new URL(request.url);
		const origin = incoming.origin;

		if (incoming.pathname === "/" && incoming.searchParams.has("q")) {
			const q = incoming.searchParams.get("q") ?? "";
			try {
				const target = resolveProxyTarget(q.startsWith("/") ? q : `/${q}`, "");
				return Response.redirect(new URL(toProxyPath(target.url), origin).href, 301);
			} catch {
				return textResponse("Invalid q parameter", 400);
			}
		}

		if (incoming.pathname === "/") {
			return new Response(request.method === "HEAD" ? null : PAGE_HTML, {
				headers: {
					"Content-Type": "text/html; charset=utf-8",
					"Cache-Control": "public, max-age=300",
				},
			});
		}

		let deep = false;
		let pathname = incoming.pathname;
		if (pathname.startsWith("/deep/")) {
			deep = true;
			pathname = `/${pathname.slice(6)}`;
		}

		let target;
		try {
			target = resolveProxyTarget(pathname, incoming.search);
		} catch (error) {
			const message = error instanceof Error ? error.message : "Invalid URL";
			return textResponse(message, message === "Target not allowed" ? 403 : 400);
		}

		if (method === "POST" && !isGitHubPostAllowed(target.url)) {
			return textResponse("POST is only allowed for GitHub", 405);
		}

		const init: RequestInit & {
			cf?: { cacheTtlByStatus: Record<string, number> };
		} = {
			method,
			headers: outboundHeaders(request, deep),
			body: method === "GET" || method === "HEAD" ? undefined : request.body,
			redirect: "follow",
		};
		if (!deep && method === "GET" && isGitHubHost(target.url.hostname)) {
			init.cf = {
				cacheTtlByStatus: { "200-299": CACHE_TTL_SECONDS, "400-599": 0 },
			};
		}

		let upstream: Response;
		try {
			upstream = await fetch(target.url, init);
		} catch (error) {
			console.warn(
				JSON.stringify({
					event: "github-proxy.upstream_failed",
					host: target.url.hostname,
					message: String(error),
				}),
			);
			return textResponse("Upstream request failed", 502);
		}

		if (deep) return deepResponse(upstream, target.url, origin);

		return new Response(upstream.body, {
			status: upstream.status,
			headers: responseHeaders(upstream),
		});
	},
} satisfies ExportedHandler;
