# Package Logic MIDI Extractor

For a Mac user who does not use development tools, distribute the self-contained executable and launcher rather than the `.bb` script. The implementation remains in [logic_extract_midi.bb](../scripts/logic_extract_midi.bb). [package.bb](../scripts/package.bb) stages that source, embeds a source-hash revision, builds an uberjar and appends it to each target's native Babashka executable. General guidance belongs to the [Clojure skill](../../clojure/references/self-contained-executables.md).

## Rebuild

1. Run the extractor tests. Run commands below from the dotagents repository root. The build machine needs `bb`, `curl`, `tar`, `zip` and `chmod`, plus network access. The recipient does not need these tools.

```bash
bb skills/logic-pro/scripts/test-logic_extract_midi.bb
bb skills/logic-pro/scripts/package.bb --help
mkdir -p .tmp
bb skills/logic-pro/scripts/package.bb --out .tmp/logic-midi-release
```

2. Use a new output directory for each build. The default targets are `macos-aarch64` (Apple Silicon) and `macos-amd64` (Intel). The directory contains a folder, `.zip` and `.zip.sha256` for each target. `.tmp/` inside it holds build inputs and is not distributed. Each package includes the executable, `Extract MIDI.command`, `README.txt`, `BUILD.txt`, the stamped extractor source and `BABASHKA-LICENSE`.
3. Check `BUILD.txt` and `--version`. The source checkout reports `2-dev`; a package reports `2+sha256-` followed by the first 16 hexadecimal characters of the original implementation's SHA-256. The manifest contains the full original hash. This identifies uncommitted implementation changes without claiming they belong to a clean Git revision. Asset or packager changes require a rebuild but do not change the implementation identifier; the archive checksum identifies the complete delivered package.
4. When updating the runtime, update `runtime-version` and every `runtime-sha256` entry in the packager together from the upstream release. The hash mismatch test must still fail. Do not replace pinned hashes with values calculated from an unverified download.
5. Test Mac execution before distribution. Both archives are unsigned and not notarised. Confirm the processor, help/version, conversion, file chooser, automatic tempo lookup and macOS security prompts after downloading and extracting the archive. Linux build success is not Mac runtime validation.

The launcher uses macOS `osascript` to choose a `.logicx` package. The CLI reads the selected alternative's binary or XML `MetaData.plist` in process on Linux and macOS, without an external utility or a default tempo assumption. `--bpm` overrides metadata; use it when metadata is missing or invalid. More than one alternative requires an explicit `--alternative N`. Existing MIDI output is never replaced.

## Linux validation

The optional `linux-amd64-static` target supports local standalone checks. It is not a Mac substitute.

```bash
bb skills/logic-pro/scripts/package.bb --out .tmp/logic-midi-linux --targets linux-amd64-static
```

Read the printed revision, then use its exact package path and identifier:

```bash
LOGIC_MIDI_TEST_BINARY=/absolute/path/to/package/logic-midi \
LOGIC_MIDI_TEST_REVISION=2+sha256-PRINTED_HASH \
bb skills/logic-pro/scripts/test-logic_extract_midi.bb
bb skills/logic-pro/scripts/test-package.bb
```

Test automatic tempo lookup without `--bpm`, using both binary and XML metadata and a non-120 tempo. Then compare with an explicit `--bpm` conversion. Use a fresh output filename when comparing source and packaged conversion output. Check each exit status and compare the resulting bytes. Test the extracted archive with no `bb` on `PATH`, including a path with spaces. The packaged source can also be run through `bb` by a developer; the user does not need to do this.
