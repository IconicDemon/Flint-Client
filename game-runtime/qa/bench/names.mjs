// Dev only: names shared by the benchmark scripts and runner.
import os from "node:os";
import path from "node:path";

export const BENCH = path.join(os.homedir(), "FlintQA", "bench");

/** A pack's file name in the test game folder: no spaces, since a tour step's words are split on spaces. */
export const packName = (name) => (name ? `bench-${name.replace(/[^A-Za-z0-9.]+/g, "-")}` : null);
