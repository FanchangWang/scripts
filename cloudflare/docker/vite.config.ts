import { cloudflare } from "@cloudflare/vite-plugin";
import { defineConfig } from "vite-plus";

export default defineConfig({
	plugins: [cloudflare()],
	fmt: { useTabs: true },
	lint: {
		options: { typeAware: true, typeCheck: true },
	},
});
