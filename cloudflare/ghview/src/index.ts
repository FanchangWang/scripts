import { PAGE_HTML } from "./page.ts";

const RAW_HOST = "raw.githubusercontent.com";
const PAGES_SUFFIX = ".github.io";
const CACHE_TTL_SECONDS = 300;

const TEXT_MIME: Record<string, string> = {
	html: "text/html; charset=utf-8",
	htm: "text/html; charset=utf-8",
	css: "text/css; charset=utf-8",
	js: "text/javascript; charset=utf-8",
	mjs: "text/javascript; charset=utf-8",
	json: "application/json; charset=utf-8",
	map: "application/json; charset=utf-8",
	xml: "application/xml; charset=utf-8",
	svg: "image/svg+xml",
	txt: "text/plain; charset=utf-8",
	md: "text/markdown; charset=utf-8",
	wasm: "application/wasm",
	ico: "image/x-icon",
};

const BINARY_MIME: Record<string, string> = {
	png: "image/png",
	jpg: "image/jpeg",
	jpeg: "image/jpeg",
	gif: "image/gif",
	webp: "image/webp",
	avif: "image/avif",
	woff: "font/woff",
	woff2: "font/woff2",
	ttf: "font/ttf",
	otf: "font/otf",
	pdf: "application/pdf",
	zip: "application/zip",
};

function contentTypeFor(pathname: string): string | null {
	const ext = pathname.split(".").pop()?.toLowerCase();
	if (!ext) return null;
	return TEXT_MIME[ext] ?? BINARY_MIME[ext] ?? null;
}

type Mode = "raw" | "pages";

interface Target {
	url: URL;
	mode: Mode;
}

type Resolution = { ok: true; target: Target } | { ok: false; status: number; message: string };

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

function isAllowedHost(hostname: string): boolean {
	return hostname === RAW_HOST || (hostname.endsWith(PAGES_SUFFIX) && hostname !== "github.io");
}

/** Reject literal and percent-encoded path traversal. */
function hasSafePath(pathname: string): boolean {
	let value = pathname;
	for (let pass = 0; pass < 2; pass++) {
		if (value.includes("\\")) return false;
		if (value.split("/").some((part) => part === "." || part === "..")) {
			return false;
		}
		try {
			value = decodeURIComponent(value);
		} catch {
			return false;
		}
	}
	return true;
}

function validateTarget(url: URL): boolean {
	if (url.protocol !== "https:" || url.port !== "" || url.username !== "" || url.password !== "") {
		return false;
	}
	return isAllowedHost(url.hostname) && hasSafePath(url.pathname);
}

/**
 * Supported forms:
 *   /gh/{owner}/{repo}/{branch}/{path}
 *   /ghpages/{owner}/{repo}/{path}
 *   /https://raw.githubusercontent.com/...
 *   /https://{owner}.github.io/{repo}/...
 */
function resolveTarget(pathname: string, search: string): Resolution {
	let url: URL;
	let mode: Mode;

	try {
		if (pathname.startsWith("/gh/")) {
			const rest = pathname.slice(4);
			if (!rest) {
				return { ok: false, status: 400, message: "Missing GitHub Raw path" };
			}
			url = new URL(`https://${RAW_HOST}/${rest}`);
			mode = "raw";
		} else if (pathname.startsWith("/ghpages/")) {
			const parts = pathname.slice(9).split("/");
			if (parts.length < 2 || !parts[0] || !parts[1]) {
				return { ok: false, status: 400, message: "Invalid GitHub Pages path" };
			}
			const owner = parts.shift()!;
			const repo = parts.shift()!;
			if (!/^[A-Za-z0-9-]+$/.test(owner) || !/^[A-Za-z0-9_.-]+$/.test(repo)) {
				return {
					ok: false,
					status: 400,
					message: "Invalid owner or repository",
				};
			}
			url = new URL(`https://${owner}.github.io/${repo}/${parts.join("/")}`);
			mode = "pages";
		} else if (pathname.startsWith("/https://") || pathname.startsWith("/http://")) {
			url = new URL(pathname.slice(1));
			if (url.protocol !== "https:") {
				return { ok: false, status: 403, message: "HTTPS required" };
			}
			mode = url.hostname === RAW_HOST ? "raw" : "pages";
		} else {
			return {
				ok: false,
				status: 400,
				message: [
					"GitHub Raw:",
					"  /gh/{owner}/{repo}/{branch}/{path}",
					"  /https://raw.githubusercontent.com/...",
					"GitHub Pages:",
					"  /ghpages/{owner}/{repo}/{path}",
					"  /https://{owner}.github.io/{repo}/...",
				].join("\n"),
			};
		}
	} catch {
		return { ok: false, status: 400, message: "Invalid URL" };
	}

	if (search) url.search = search;

	if (!validateTarget(url)) {
		return {
			ok: false,
			status: 403,
			message: "Target host or path not allowed",
		};
	}

	return { ok: true, target: { url, mode } };
}

/**
 * Root-relative subresources (`/styles/app.css`) lose the proxy prefix.
 * Browsers send the document URL in Referer, so reconstruct the upstream path
 * from it. This is stateless and also covers URLs built at runtime by scripts.
 */
function resolveFromReferer(request: Request, incoming: URL): Resolution {
	const invalid: Resolution = {
		ok: false,
		status: 400,
		message: "Unsupported path",
	};
	const referer = request.headers.get("Referer");
	if (!referer) return invalid;

	let ref: URL;
	try {
		ref = new URL(referer);
	} catch {
		return invalid;
	}
	if (ref.origin !== incoming.origin) return invalid;

	const slash = ref.pathname.lastIndexOf("/");
	const basePath = ref.pathname.slice(0, slash + 1);
	if (
		!basePath.startsWith("/gh/") &&
		!basePath.startsWith("/ghpages/") &&
		!/^\/https?:\/\//.test(basePath)
	) {
		return invalid;
	}

	return resolveTarget(`${basePath}${incoming.pathname.replace(/^\//, "")}${incoming.search}`, "");
}

export default {
	async fetch(request) {
		if (request.method !== "GET" && request.method !== "HEAD") {
			return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
		}

		const incoming = new URL(request.url);
		if (incoming.pathname === "/") {
			return new Response(request.method === "HEAD" ? null : PAGE_HTML, {
				headers: {
					"Content-Type": "text/html; charset=utf-8",
					"Cache-Control": "public, max-age=300",
				},
			});
		}

		let resolution = resolveTarget(incoming.pathname, incoming.search);
		if (!resolution.ok) {
			const viaReferer = resolveFromReferer(request, incoming);
			if (viaReferer.ok) resolution = viaReferer;
		}
		if (!resolution.ok) {
			return textResponse(resolution.message, resolution.status);
		}

		const { url, mode } = resolution.target;

		let upstream: Response;
		try {
			upstream = await fetch(url, {
				method: request.method,
				headers: {
					Accept: request.headers.get("Accept") ?? "*/*",
					"User-Agent": "GHView-Worker",
				},
				redirect: "follow",
				cf: {
					cacheTtlByStatus: { "200-299": CACHE_TTL_SECONDS, "400-599": 0 },
				},
			});
		} catch (error) {
			console.warn(
				JSON.stringify({
					event: "ghview.upstream_failed",
					host: url.hostname,
					message: String(error),
				}),
			);
			return textResponse("Upstream request failed", 502);
		}

		const headers = new Headers(upstream.headers);
		for (const name of [
			"Content-Security-Policy",
			"Content-Security-Policy-Report-Only",
			"Content-Disposition",
			"Set-Cookie",
		]) {
			headers.delete(name);
		}
		headers.set("X-Content-Type-Options", "nosniff");
		headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
		if (mode === "raw") {
			const mime = contentTypeFor(url.pathname);
			if (mime) headers.set("Content-Type", mime);
		}
		if (!upstream.ok) headers.set("Cache-Control", "no-store");

		return new Response(upstream.body, { status: upstream.status, headers });
	},
} satisfies ExportedHandler;
