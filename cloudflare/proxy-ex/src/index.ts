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
	return headers;
}

export default {
	async fetch(request) {
		const method = request.method;
		if (method !== "GET" && method !== "HEAD") {
			return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
		}

		const incoming = new URL(request.url);

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
				return Response.redirect(new URL(canonical, incoming.href).href, 302);
			}
			const message = error instanceof Error ? error.message : "Invalid URL";
			return textResponse(message, message === "Target not allowed" ? 403 : 400);
		}

		let upstream: Response;
		try {
			upstream = await fetch(target.url, {
				method,
				headers: outboundHeaders(request),
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
