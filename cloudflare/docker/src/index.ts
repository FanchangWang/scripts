import { PAGE_HTML } from "./page.ts";
import { normalizeDockerHubPath, rewriteRealm, routeFor } from "./registry.ts";

function textResponse(message: string, status: number): Response {
	return new Response(message, {
		status,
		headers: { "Content-Type": "text/plain; charset=utf-8" },
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
	]);
	for (const [key, value] of request.headers.entries()) {
		if (!stripped.has(key.toLowerCase())) headers.set(key, value);
	}
	return headers;
}

function responseHeaders(upstream: Response, origin: string, isDockerHub: boolean): Headers {
	const headers = new Headers(upstream.headers);
	headers.delete("Set-Cookie");
	const auth = headers.get("WWW-Authenticate");
	const rewritten = rewriteRealm(auth, origin, isDockerHub);
	if (rewritten !== null) headers.set("WWW-Authenticate", rewritten);
	return headers;
}

export default {
	async fetch(request, env) {
		const incoming = new URL(request.url);
		const origin = incoming.origin;

		if (incoming.pathname === "/") {
			return new Response(PAGE_HTML, {
				headers: {
					"Content-Type": "text/html; charset=utf-8",
					"Cache-Control": "public, max-age=300",
				},
			});
		}

		if (incoming.pathname === "/token") {
			const tokenUrl = new URL("https://auth.docker.io/token");
			tokenUrl.search = incoming.search;
			const basic = btoa(`${env.DOCKERHUB_USERNAME ?? ""}:${env.DOCKERHUB_PAT ?? ""}`);
			try {
				const upstream = await fetch(tokenUrl, {
					headers: {
						Authorization: `Basic ${basic}`,
						Accept: "application/json",
						"User-Agent": request.headers.get("User-Agent") ?? "docker-registry-proxy",
					},
					redirect: "follow",
				});
				return new Response(upstream.body, {
					status: upstream.status,
					headers: upstream.headers,
				});
			} catch {
				return textResponse("Token request failed", 502);
			}
		}

		const route = routeFor(incoming.hostname, incoming.searchParams.get("ns"));
		let pathname = incoming.pathname;
		if (route.isDockerHub) pathname = normalizeDockerHubPath(pathname);

		try {
			const upstream = await fetch(`https://${route.host}${pathname}`, {
				method: request.method,
				headers: outboundHeaders(request),
				body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
				redirect: "follow",
			});
			return new Response(upstream.body, {
				status: upstream.status,
				headers: responseHeaders(upstream, origin, route.isDockerHub),
			});
		} catch {
			return textResponse("Upstream request failed", 502);
		}
	},
} satisfies ExportedHandler<Env>;
