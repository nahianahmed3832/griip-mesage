import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 20000,
    hookTimeout: 20000,
    pool: "forks",
    // A local MongoDB replica set for the MongoDB tests (test/support/mongoGlobal.ts).
    globalSetup: ["test/support/mongoGlobal.ts"],
  },
});
