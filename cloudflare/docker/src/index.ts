import { normalizeSearch, normalizeTags, parseRepo } from "./hub.ts";
import { PAGE_HTML } from "./page.ts";
import { normalizeDockerHubPath, rewriteRealm, routeFor } from "./registry.ts";

const HUB_CACHE_TTL = 60;

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

function intParam(value: string | null, fallback: number, min: number, max: number): number {
	const parsed = Number(value);
	return Number.isInteger(parsed) && parsed >= min ? Math.min(parsed, max) : fallback;
}

async function hubApiToken(env: Env, ctx: ExecutionContext): Promise<string> {
	const cache = caches.default;
	const key = new Request("https://hub-token.local/");
	const hit = await cache.match(key);
	if (hit) return hit.text();

	const response = await fetch("https://hub.docker.com/v2/users/login/", {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({
			username: env.DOCKERHUB_USERNAME ?? "",
			password: env.DOCKERHUB_PAT ?? "",
		}),
	});
	if (!response.ok) throw new Error("Hub login failed");
	const data = (await response.json()) as { token?: string };
	if (!data.token) throw new Error("Hub login returned no token");
	ctx.waitUntil(
		cache.put(
			key,
			new Response(data.token, {
				headers: { "Cache-Control": "public, max-age=600" },
			}),
		),
	);
	return data.token;
}

async function cachedHubJson(url: URL, env: Env, ctx: ExecutionContext): Promise<Response> {
	const cache = caches.default;
	const key = new Request(url.toString(), { method: "GET" });
	const hit = await cache.match(key);
	if (hit) {
		const headers = new Headers(hit.headers);
		headers.set("X-Cache", "HIT");
		return new Response(hit.body, { headers });
	}

	const token = await hubApiToken(env, ctx);
	const upstream = await fetch(url, {
		headers: {
			Accept: "application/json",
			Authorization: `Bearer ${token}`,
			"User-Agent": "docker-registry-search",
		},
		redirect: "follow",
	});
	if (!upstream.ok) return upstream;

	const body = await upstream.text();
	const response = new Response(body, {
		status: upstream.status,
		headers: {
			"Content-Type": "application/json; charset=utf-8",
			"Cache-Control": `public, max-age=${HUB_CACHE_TTL}`,
			"X-Cache": "MISS",
		},
	});
	ctx.waitUntil(cache.put(key, response.clone()));
	return response;
}

function jsonResponse(value: unknown, cacheStatus: string): Response {
	const headers: Record<string, string> = {
		"Cache-Control": `public, max-age=${HUB_CACHE_TTL}`,
	};
	if (cacheStatus) headers["X-Cache"] = cacheStatus;
	return Response.json(value, { headers });
}

export default {
	async fetch(request, env, ctx) {
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

		if (incoming.pathname === "/search") {
			const query = (incoming.searchParams.get("q") ?? "").trim();
			if (!query || query.length > 100) return textResponse("Invalid q", 400);
			const page = intParam(incoming.searchParams.get("page"), 1, 1, 100);
			const size = intParam(incoming.searchParams.get("page_size"), 20, 1, 50);
			const url = new URL("https://hub.docker.com/v2/search/repositories/");
			url.searchParams.set("query", query);
			url.searchParams.set("page", String(page));
			url.searchParams.set("page_size", String(size));
			try {
				const response = await cachedHubJson(url, env, ctx);
				if (!response.ok) return response;
				return jsonResponse(
					normalizeSearch(await response.json()),
					response.headers.get("X-Cache") ?? "",
				);
			} catch {
				return textResponse("Search failed", 502);
			}
		}

		if (incoming.pathname === "/tags") {
			const repo = (incoming.searchParams.get("repo") ?? "").trim();
			const parsed = parseRepo(repo);
			if (!parsed) return textResponse("Invalid repo", 400);
			const name = (incoming.searchParams.get("name") ?? "").trim();
			if (name.length > 100) return textResponse("Invalid name", 400);
			const page = intParam(incoming.searchParams.get("page"), 1, 1, 100);
			const size = intParam(incoming.searchParams.get("page_size"), 20, 1, 50);
			const url = new URL(
				`https://hub.docker.com/v2/namespaces/${encodeURIComponent(parsed.namespace)}/repositories/${encodeURIComponent(parsed.repository)}/tags/`,
			);
			if (name) url.searchParams.set("name", name);
			url.searchParams.set("page", String(page));
			url.searchParams.set("page_size", String(size));
			try {
				const response = await cachedHubJson(url, env, ctx);
				if (!response.ok) return response;
				return jsonResponse(
					normalizeTags(await response.json()),
					response.headers.get("X-Cache") ?? "",
				);
			} catch {
				return textResponse("Tag lookup failed", 502);
			}
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
