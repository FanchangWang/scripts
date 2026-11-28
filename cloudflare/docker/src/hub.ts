export interface SearchResult {
	name: string;
	description: string;
	stars: number;
	pulls: number;
	official: boolean;
}

export interface SearchPayload {
	count: number;
	results: SearchResult[];
}

export interface TagResult {
	name: string;
	digest: string | null;
	updated: string | null;
}

export interface TagPayload {
	count: number;
	results: TagResult[];
}

const REPO_RE = /^[A-Za-z0-9][A-Za-z0-9._-]*(?:\/[A-Za-z0-9][A-Za-z0-9._-]*)?$/;

export function parseRepo(repo: string): { namespace: string; repository: string } | null {
	if (!REPO_RE.test(repo)) return null;
	const parts = repo.split("/");
	if (parts.length === 1) return { namespace: "library", repository: parts[0] };
	return { namespace: parts[0], repository: parts[1] };
}

function asRecord(value: unknown): Record<string, unknown> | null {
	return value !== null && typeof value === "object" && !Array.isArray(value)
		? (value as Record<string, unknown>)
		: null;
}

function asString(value: unknown): string {
	return typeof value === "string" ? value : "";
}

export function normalizeSearch(value: unknown): SearchPayload {
	const root = asRecord(value);
	const rows = Array.isArray(root?.results) ? root.results : [];
	return {
		count: typeof root?.count === "number" ? root.count : 0,
		results: rows
			.map((row) => {
				const item = asRecord(row) ?? {};
				return {
					name: asString(item.repo_name),
					description: asString(item.short_description),
					stars: typeof item.star_count === "number" ? item.star_count : 0,
					pulls: typeof item.pull_count === "number" ? item.pull_count : 0,
					official: item.is_official === true,
				};
			})
			.filter((item) => item.name !== ""),
	};
}

export function normalizeTags(value: unknown): TagPayload {
	const root = asRecord(value);
	const rows = Array.isArray(root?.results) ? root.results : [];
	return {
		count: typeof root?.count === "number" ? root.count : 0,
		results: rows
			.map((row) => {
				const item = asRecord(row) ?? {};
				const images = Array.isArray(item.images) ? item.images : [];
				const first = asRecord(images[0]) ?? {};
				return {
					name: asString(item.name),
					digest:
						typeof item.digest === "string"
							? item.digest
							: typeof first.digest === "string"
								? first.digest
								: null,
					updated: typeof item.last_updated === "string" ? item.last_updated : null,
				};
			})
			.filter((item) => item.name !== ""),
	};
}
