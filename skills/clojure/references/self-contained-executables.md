# Self-contained Babashka executables

Use this process to distribute a Babashka CLI to someone who must not need a runtime installation. Keep the implementation in Clojure; package an uberjar with a native Babashka runtime. The [upstream instructions](https://github.com/babashka/babashka/wiki/Self-contained-executable#uberjar) describe this method and require Babashka 1.3.186 or later.

## Build

1. Provide a namespace with `-main`. Put error handling inside `-main`, not only in the script execution guard: the uberjar calls `-main` directly. Keep interactive file selection in a separate launcher so the CLI remains usable without prompts.
2. Support `--help`, `--version`, clear exit codes and errors on stderr. Validate options before opening files. For a file converter, refuse existing output files with `CREATE_NEW`, including symlinks and the input itself. Tell users that a failed write can leave a partial output file.
3. Stage the source under the namespace's classpath filename, for example `src/logic_extract_midi.clj` for `logic-extract-midi`. Use an isolated `bb.edn` with `{:paths ["src"]}` so the caller's project dependencies cannot enter the package.
4. Embed a revision in the staged source. Hash the original implementation bytes before stamping and record the full hash in the package manifest. Do not label a dirty source tree with only its Git commit. Keep source, manifest and executable together for diagnosis.
5. Build the uberjar, then test its application entry point. Use `--` before application options: without it, `bb --jar app.jar --version` can report the Babashka version instead of the application revision.

```bash
bb uberjar app.jar -m your.main
bb --jar app.jar -- --version
bb --jar app.jar -- --help
```

6. Download the exact Babashka release archive for each target OS and processor. Pin the release and its archive SHA-256; reject a mismatch before extracting or executing the runtime. Obtain hashes from the upstream release, not from the downloaded file alone. Check the hash guard with both a valid file and an altered copy.
7. Append the uberjar bytes to the native `bb` executable and set executable permissions. This is binary concatenation, not text concatenation. The resulting executable invokes the jar's `-main` without a separate Java or Babashka installation. Build separate archives for separate processors; cross-building does not establish target compatibility.
8. Include a short user README, revision/build manifest, implementation source and runtime licence/source attribution. Preserve executable permissions when archiving. Keep work files out of the distributed archive. Use a fresh output directory, and report success only after all requested targets finish.

## Validate

Run the converter's contract tests against the source script and against the standalone executable. Extract the finished archive into a directory with spaces and test it without `bb` on `PATH`. Compare real conversion output byte-for-byte with the source implementation. Check help, revision, errors, overwrite refusal and exit status, not just file creation.

Validate the outer distribution archive separately from the executable. In the Logic extractor checks, `unzip -t` rejected the combined native executable with ZIP warnings, although Babashka ran it successfully. Compare the executable's trailing bytes with the built uberjar and execute it; do not use a ZIP tool's verdict on the combined file as the sole executable check.

On a Mac, test each supported processor, metadata utilities, the file chooser, permission preservation after extraction, and downloaded/quarantined execution. Appending data can affect signing checks; do not infer Gatekeeper acceptance from an upstream runtime signature or from Linux tests. Use the supported signing and notarisation workflow if trusted distribution requires it. Do not tell users to disable Gatekeeper. Link [Apple's guidance](https://support.apple.com/en-gb/102445) for security prompts, and label packages unverified until the target tests pass.

## Repository example

The Logic Pro skill owns the concrete [packager](../../logic-pro/scripts/package.bb), [user assets](../../logic-pro/assets/) and [rebuild instructions](../../logic-pro/references/packaging.md). Use that implementation as an example, not as a universal packaging API. Its runtime release, target hashes, entry namespace and revision stamping are application-specific.
