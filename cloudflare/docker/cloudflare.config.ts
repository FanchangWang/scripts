import { bindings, defineConfig } from "cf/config";
import * as entrypoint from "./src/index.ts" with { type: "cf-worker" };

export default defineConfig({
	worker: {
		name: "docker",
		// Latest date supported by the installed workerd; bump after toolchain upgrade.
		compatibilityDate: "2026-11-23",
		entrypoint,
		domains: ["docker.allproxy.dpdns.org"],
		workersDev: true,
		env: {
			DOCKERHUB_USERNAME: bindings.secret(),
			DOCKERHUB_PAT: bindings.secret(),
		},
		observability: {
			enabled: true,
			logs: { enabled: true, invocationLogs: true },
			traces: { enabled: true },
		},
	},
});
