export interface ProxyTarget {
	url: URL;
	kind: "full" | "host" | "shorthand";
}

const GITHUB_HOST = "github.com";
const RAW_HOST = "raw.githubusercontent.com";
const GIST_HOST = "gist.githubusercontent.com";
const API_HOST = "api.github.com";

const ALLOWED_HOSTS = new Set([
	"github.com",
	"api.github.com",
	"raw.githubusercontent.com",
	"raw.github.com",
	"gist.github.com",
	"gist.githubusercontent.com",
	"objects.githubusercontent.com",
	"codeload.github.com",
	"github.githubassets.com",
	"opengraph.githubassets.com",
	"avatars.githubusercontent.com",
	"desktop.githubusercontent.com",
	"gh.io",
	"github.io",
]);

export function isAllowedGitHubHost(hostname: string): boolean {
	const host = hostname.toLowerCase();
	return ALLOWED_HOSTS.has(host) || host.endsWith(".github.com") || host.endsWith(".github.io");
}

const KNOWN_GITHUB_SEGMENTS = new Set([
	"releases",
	"archive",
	"blob",
	"raw",
	"commit",
	"pull",
	"tree",
	"issues",
	"compare",
	"wiki",
	"actions",
	"suites",
	"tags",
	"commits",
	"info",
	"git-upload-pack",
	"git-receive-pack",
]);

function looksLikeHost(segment: string): boolean {
	return segment.includes(".") && !segment.includes("/");
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
	if (
		host === "localhost" ||
		host.endsWith(".localhost") ||
		host.endsWith(".local") ||
		host.endsWith(".internal") ||
		isIpLiteral(host)
	) {
		return false;
	}
	return isAllowedGitHubHost(host);
}

export function isGitHubPostAllowed(url: URL): boolean {
	return (
		url.protocol === "https:" && url.hostname.toLowerCase() === "github.com" && url.port === ""
	);
}

export const isGitHubHost = isAllowedGitHubHost;

function shorthandToUrl(rest: string): string {
	const parts = rest.split("/").filter(Boolean);
	if (parts.length === 0) throw new Error("Empty path");
	const first = parts[0];

	if (first === "gist") {
		return `https://${GIST_HOST}/${parts.slice(1).join("/")}`;
	}
	if (first === API_HOST) {
		return `https://${API_HOST}/${parts.slice(1).join("/")}`;
	}
	if (parts.length <= 2 || parts[1].endsWith(".git")) {
		return `https://${GITHUB_HOST}/${rest}`;
	}

	const marker = parts[2];
	if (marker === "raw" || marker === "blob") {
		const bare = [parts[0], parts[1], ...parts.slice(3)];
		return `https://${RAW_HOST}/${bare.join("/")}`;
	}
	if (KNOWN_GITHUB_SEGMENTS.has(marker)) {
		return `https://${GITHUB_HOST}/${rest}`;
	}

	return `https://${RAW_HOST}/${rest}`;
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
		raw = shorthandToUrl(rest);
		kind = "shorthand";
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
