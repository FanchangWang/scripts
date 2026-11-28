export interface ProxyTarget {
	url: URL;
	kind: "full" | "host";
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
	} else if (first.includes(".")) {
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
 * Root-relative subresources lose the proxy prefix. Recover the upstream base
 * from the document URL carried in Referer, without cookies or URL rewriting.
 */
export function resolveFromReferer(request: Request, incoming: URL): ProxyTarget | null {
	const referer = request.headers.get("Referer");
	if (!referer) return null;

	let ref: URL;
	try {
		ref = new URL(referer);
	} catch {
		return null;
	}
	if (ref.origin !== incoming.origin) return null;

	const slash = ref.pathname.lastIndexOf("/");
	const basePath = ref.pathname.slice(0, slash + 1);
	const first = basePath.replace(/^\//, "").split("/")[0] ?? "";
	if (
		!basePath.startsWith("/https://") &&
		!basePath.startsWith("/http://") &&
		!first.includes(".")
	) {
		return null;
	}

	try {
		return resolveProxyTarget(
			`${basePath}${incoming.pathname.replace(/^\//, "")}${incoming.search}`,
			"",
		);
	} catch {
		return null;
	}
}
