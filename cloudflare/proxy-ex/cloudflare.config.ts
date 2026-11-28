import { defineConfig } from "cf/config";
import * as entrypoint from "./src/index.ts" with { type: "cf-worker" };

export default defineConfig({
	worker: {
		name: "proxy-ex",
		// Latest date supported by the installed workerd; bump after toolchain upgrade.
		compatibilityDate: "2026-11-23",
		entrypoint,
		domains: ["proxy.allproxy.dpdns.org"],
		workersDev: true,
		observability: {
			enabled: true,
			logs: { enabled: true, invocationLogs: true },
			traces: { enabled: true },
		},
	},
});
