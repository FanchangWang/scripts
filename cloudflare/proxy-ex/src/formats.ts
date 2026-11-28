export interface ProxyTarget {
	url: URL;
	kind: "full" | "host";
}

/** Static file names can contain dots but are never hostnames. */
const FILE_EXTENSIONS = new Set([
	"js",
	"css",
	"json",
	"map",
	"xml",
	"html",
	"htm",
	"txt",
	"md",
	"png",
	"jpg",
	"jpeg",
	"gif",
	"webp",
	"avif",
	"svg",
	"ico",
	"bmp",
	"woff",
	"woff2",
	"ttf",
	"otf",
	"eot",
	"pdf",
	"zip",
	"gz",
	"tar",
	"mp3",
	"mp4",
	"webm",
	"wasm",
	"php",
	"asp",
	"aspx",
	"jsp",
]);

function looksLikeHost(segment: string): boolean {
	if (!segment.includes(".") || segment.includes("/")) return false;
	const last = segment.split(".").pop() ?? "";
	return last.length >= 2 && !FILE_EXTENSIONS.has(last.toLowerCase());
}

function isIpLiteral(host: string): boolean {
	if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
		const parts = host.split(".").map(Number);
		if (parts.some((n) => n > 255)) return true;
		const [a, b] = parts;
		return (
			a === 0 ||
			a === 10 ||
			a === 127 ||
			(a === 169 && b === 254) ||
			(a === 172 && b >= 16 && b <= 31) ||
			(a === 192 && b === 168) ||
			a >= 224
		);
	}
	return host.includes(":");
}

export function validateUrl(url: URL): boolean {
	if (url.protocol !== "http:" && url.protocol !== "https:") return false;
	if (url.username || url.password) return false;
	if (url.port && url.port !== "80" && url.port !== "443") return false;

	const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
	return !(
		host === "localhost" ||
		host.endsWith(".localhost") ||
		host.endsWith(".local") ||
		host.endsWith(".internal") ||
		isIpLiteral(host)
	);
}

export function resolveProxyTarget(pathname: string, search: string): ProxyTarget {
	const rest = pathname.replace(/^\//, "");
	const first = rest.split("/")[0] ?? "";

	let raw: string;
	let kind: ProxyTarget["kind"];
	if (pathname.startsWith("/https://") || pathname.startsWith("/http://")) {
		raw = pathname.slice(1);
		kind = "full";
	} else if (looksLikeHost(first)) {
		raw = `https://${rest}`;
		kind = "host";
	} else {
		throw new Error("Invalid URL");
	}

	let url: URL;
	try {
		url = new URL(raw);
	} catch {
		throw new Error("Invalid URL");
	}
	if (search) url.search = search;
	if (!validateUrl(url)) throw new Error("Target not allowed");

	return { url, kind };
}

export function toProxyPath(url: URL): string {
	return `/${url.protocol}//${url.host}${url.pathname}${url.search}`;
}

/**
 * Recover the upstream origin root from a proxied document URL. Only the
 * origin is used: paths like `/static/a.js` are root-relative on the upstream,
 * not relative to the current document directory.
 */
function upstreamRootFromReferer(refPath: string): string | null {
	if (refPath.startsWith("/https://") || refPath.startsWith("/http://")) {
		try {
			const url = new URL(refPath.slice(1));
			if (url.protocol !== "http:" && url.protocol !== "https:") return null;
			return `/${url.protocol}//${url.host}/`;
		} catch {
			return null;
		}
	}
	const first = refPath.split("/")[1] ?? "";
	return looksLikeHost(first) ? `/${first}/` : null;
}

/**
 * Build the canonical proxy path for a bare request that lost its upstream
 * prefix. The caller should redirect to it before serving content, so the
 * document URL itself keeps the upstream origin for later Referer lookups.
 */
export function canonicalFromReferer(request: Request, incoming: URL): string | null {
	const referer = request.headers.get("Referer");
	if (!referer) return null;

	let ref: URL;
	try {
		ref = new URL(referer);
	} catch {
		return null;
	}
	if (ref.origin !== incoming.origin) return null;

	const root = upstreamRootFromReferer(ref.pathname);
	if (!root) return null;
	return `${root}${incoming.pathname.replace(/^\//, "")}${incoming.search}`;
}
