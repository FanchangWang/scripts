import { canonicalFromReferer, resolveProxyTarget, toProxyPath } from "./formats.ts";
import { PAGE_HTML } from "./page.ts";

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

function outboundHeaders(request: Request): Headers {
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
	return headers;
}

function passthroughHeaders(upstream: Response): Headers {
	const headers = new Headers(upstream.headers);
	headers.delete("Content-Security-Policy");
	headers.delete("Content-Security-Policy-Report-Only");
	headers.delete("Set-Cookie");
	// Never let the edge cache a proxy response: stale/old-version responses
	// would otherwise survive deployments for hours.
	headers.delete("Cache-Control");
	headers.delete("Age");
	headers.delete("Expires");
	headers.delete("ETag");
	headers.set("Cache-Control", "no-store");
	return headers;
}

const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function hasProxyReferer(request: Request, incoming: URL): boolean {
	const referer = request.headers.get("Referer");
	if (!referer) return false;
	try {
		const ref = new URL(referer);
		return ref.host === incoming.host && ref.protocol === incoming.protocol;
	} catch {
		return false;
	}
}

function preflightResponse(request: Request): Response {
	return new Response(null, {
		status: 204,
		headers: {
			"Access-Control-Allow-Origin": "*",
			"Access-Control-Allow-Methods": "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS",
			"Access-Control-Allow-Headers": request.headers.get("Access-Control-Request-Headers") ?? "*",
			"Access-Control-Max-Age": "86400",
		},
	});
}

export default {
	async fetch(request) {
		const method = request.method;
		const incoming = new URL(request.url);

		if (method === "OPTIONS") return preflightResponse(request);
		if (MUTATING_METHODS.has(method) && !hasProxyReferer(request, incoming)) {
			return textResponse("Referer required", 403);
		}

		if (incoming.pathname === "/" && incoming.searchParams.has("q")) {
			const q = incoming.searchParams.get("q") ?? "";
			try {
				const target = resolveProxyTarget(q.startsWith("/") ? q : `/${q}`, "");
				return Response.redirect(new URL(toProxyPath(target.url), incoming.origin).href, 301);
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

		let target;
		try {
			target = resolveProxyTarget(incoming.pathname, incoming.search);
		} catch (error) {
			const canonical = canonicalFromReferer(request, incoming);
			if (canonical) {
				const dest = request.headers.get("Sec-Fetch-Dest") ?? "";
				const accept = request.headers.get("Accept") ?? "";
				const isDocument =
					dest === "document" || (method === "GET" && accept.includes("text/html"));
				if (isDocument) {
					return Response.redirect(new URL(canonical, incoming.href).href, 302);
				}
				// Subresources and API calls must keep their method/body and must not
				// be redirected; serve them in place using the recovered upstream URL.
				try {
					target = resolveProxyTarget(canonical, "");
				} catch {
					const message = error instanceof Error ? error.message : "Invalid URL";
					return textResponse(message, message === "Target not allowed" ? 403 : 400);
				}
			} else {
				const message = error instanceof Error ? error.message : "Invalid URL";
				return textResponse(message, message === "Target not allowed" ? 403 : 400);
			}
		}

		let upstream: Response;
		try {
			upstream = await fetch(target.url, {
				method,
				headers: outboundHeaders(request),
				body:
					method === "GET" || method === "HEAD" || method === "OPTIONS" ? undefined : request.body,
				redirect: "follow",
			});
		} catch (error) {
			console.warn(
				JSON.stringify({
					event: "proxy-ex.upstream_failed",
					host: target.url.hostname,
					message: String(error),
				}),
			);
			return textResponse("Upstream request failed", 502);
		}

		return new Response(upstream.body, {
			status: upstream.status,
			headers: passthroughHeaders(upstream),
		});
	},
} satisfies ExportedHandler;
