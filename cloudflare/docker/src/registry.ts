export interface RegistryRoute {
	host: string;
	isDockerHub: boolean;
}

const REGISTRIES: Record<string, string> = {
	quay: "quay.io",
	gcr: "gcr.io",
	"k8s-gcr": "k8s.gcr.io",
	k8s: "registry.k8s.io",
	ghcr: "ghcr.io",
	cloudsmith: "docker.cloudsmith.io",
	nvcr: "nvcr.io",
};

export function routeFor(hostname: string, ns: string | null): RegistryRoute {
	if (ns) {
		return {
			host: ns === "docker.io" ? "registry-1.docker.io" : ns,
			isDockerHub: ns === "docker.io",
		};
	}
	const prefix = hostname.split(".")[0];
	if (prefix in REGISTRIES) {
		return { host: REGISTRIES[prefix], isDockerHub: false };
	}
	return { host: "registry-1.docker.io", isDockerHub: true };
}

/** Docker Hub shorthand `nginx` must become `library/nginx`. */
export function normalizeDockerHubPath(pathname: string): string {
	if (/^\/v2\/[^/]+\/(manifests|blobs)\/[^/]+$/.test(pathname)) {
		return pathname.replace(/^\/v2\//, "/v2/library/");
	}
	return pathname;
}

export function rewriteRealm(
	value: string | null,
	origin: string,
	isDockerHub: boolean,
): string | null {
	if (!value || !isDockerHub) return value;
	return value.replace("https://auth.docker.io/token", `${origin}/token`);
}
