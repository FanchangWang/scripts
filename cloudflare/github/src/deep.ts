const REWRITE_HOSTS = [
	"api.github.com",
	"raw.githubusercontent.com",
	"raw.github.com",
	"gist.githubusercontent.com",
	"gist.github.com",
	"objects.githubusercontent.com",
	"codeload.github.com",
	"github.githubassets.com",
	"opengraph.githubassets.com",
	"avatars.githubusercontent.com",
	"desktop.githubusercontent.com",
	"github.com",
];

const MARK = "\u0000__GHDEEP__\u0000";
const APP_JSON_RE = new RegExp("application/(json|javascript|x-javascript|xml|yaml)");
const TEXT_EXT_RE = new RegExp(
	"\\.(ps1|psm1|sh|bash|zsh|js|mjs|cjs|ts|json|ya?ml|xml|css|html?|md|txt|toml|ini|bat|cmd|py|rb|pl)$",
	"i",
);

export const MAX_DEEP_BYTES = 2 * 1024 * 1024;

function escapeRegExp(value: string): string {
	return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function isTextual(contentType: string, pathname: string): boolean {
	const ct = contentType.toLowerCase();
	if (ct.startsWith("text/") || ct.includes("+json")) return true;
	if (APP_JSON_RE.test(ct)) return true;
	return TEXT_EXT_RE.test(pathname);
}

/** Rewrite absolute GitHub URLs in text/JSON so they proxy through this Worker. */
export function rewriteText(text: string, origin: string): string {
	// Hide already-proxied URLs so the replacement below cannot nest them.
	const httpsMark = `${MARK}s://`;
	const httpMark = `${MARK}p://`;
	let out = text
		.split(`${origin}/deep/https://`)
		.join(httpsMark)
		.split(`${origin}/deep/http://`)
		.join(httpMark);

	for (const host of REWRITE_HOSTS) {
		const re = new RegExp(`https?://${escapeRegExp(host)}([^\\s"'<>\`\\)\\]}]*)`, "gi");
		out = out.replace(re, (full) => `${origin}/deep/${full}`);
	}

	return out
		.split(httpsMark)
		.join(`${origin}/deep/https://`)
		.split(httpMark)
		.join(`${origin}/deep/http://`);
}
