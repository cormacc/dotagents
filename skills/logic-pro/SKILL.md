---
name: logic-pro
description: Read Apple Logic Pro projects (.logicx bundles) without Logic -- survey the binary ProjectData file and extract MIDI regions to a Standard MIDI File for import into another DAW. Use whenever the user has a .logicx project, a ProjectData file, or a zipped Logic session and wants its MIDI, notes, tempo, tracks or other content, even if they only say "a Logic session" or "get the piano part out".
compatibility: Needs Babashka. The format knowledge comes from one Logic Pro 11.2 project; check other versions with the survey and the tempo check.
---

# Logic Pro projects

Logic Pro stores a project in a binary `ProjectData` file that Apple does not document. `scripts/logic_extract_midi.bb` surveys that file and extracts its MIDI notes. [references/projectdata-format.md](references/projectdata-format.md) documents the format, the evidence for each field, and what is not decoded. Read that reference before you interpret survey output that the steps below do not explain.

The format knowledge comes from one project, saved by Logic Pro 11.2. Thus each step below includes a check that fails if a different project does not match the known format. Do not skip the checks to get output faster: a wrong layout gives a MIDI file that loads without error but contains wrong notes.

## Extract MIDI

1. **Find the project data.** Unzip a zipped session to a scratch directory. Read `Resources/ProjectInformation.plist` for `LastSavedFrom` and `VariantNames`, and `Alternatives/NNN/MetaData.plist` for `BeatsPerMinute`, the time signature and `NumberOfTracks`. The reference shows how to read a binary plist. If there is more than one alternative, ask the user which one to use.

2. **Survey.** Run:

   ```bash
   bb <skill-dir>/scripts/logic_extract_midi.bb --list [--alternative N] <project.logicx>
   ```

   It prints the count of each chunk tag, then one line for each event list (`EvSq`): its offset, its size, the name of its sequence (`MSeq`), its note and pressure counts, and the count of each event status. A sequence with `0x90` events holds notes. If a status that the reference does not describe holds musical data (for example `0xb0` or `0xe0`), tell the user that the script does not export it.

3. **Extract.**

   ```bash
   bb <skill-dir>/scripts/logic_extract_midi.bb --bpm <BeatsPerMinute> [--alternative N] <project.logicx> <out.mid>
   ```

   The output is a type 1 Standard MIDI File at 960 ticks per quarter note. Track 1 holds the tempo and the time signature (4/4: the script does not read the project signature). Each later track holds one sequence that has notes, with the sequence name as the track name. Song bar 1 is MIDI tick 0.

4. **Check.**
   - The exit status is 0. A non-zero status with `tempo-map drift` means that the project has a tempo change, or `--bpm` is wrong. Do not raise `--max-drift-ms` to pass the check: the script cannot write a tempo map, so the timing would be wrong.
   - The note counts agree with the survey.
   - The pitch and velocity ranges suit the instrument. Compare the part's length with the audio files in `Media/Audio Files/`, if there are any.
   - Optional: load the file with a MIDI library, for example `nix-shell -p python3Packages.mido`, and count the `note_on` messages.

5. **Report the limits** that apply to the result: no note-off velocity (the script writes 64), `0xD0` events written as channel pressure without confirmation, and region playback parameters (transpose, quantise, loop, trim) not applied. See [references/projectdata-format.md#Not verified](references/projectdata-format.md#not-verified).

## When the format does not match

If the script fails on a note record, or the tempo check fails at the correct tempo, the project uses a layout that the reference does not describe. Investigate before you change the script:

- Count the `(byte 0, byte 7)` pairs of the 16-byte records in the large `EvSq` chunk, and hex-dump the first 64 bytes. A repeating group of records that has one start record (bit 7 of byte 7 clear) is one event.
- Use the microsecond time record to find the tick origin and the tempo. It is independent of the tick field.
- Use `WindowImage.jpg` (a screenshot of the arrange window) and the user's knowledge of the project as a positive control: the expected track names, region positions and note ranges.

When you confirm a new fact, add it to the reference with the Logic version and the date, add a case to `scripts/test-logic_extract_midi.bb`, and run the test.

## Scripts

| Script | Use |
| --- | --- |
| `scripts/logic_extract_midi.bb` | Survey a project (`--list`) or extract its MIDI notes, with the tempo check. |
| `scripts/test-logic_extract_midi.bb` | Tests the extractor against a synthetic `ProjectData` file. Run it after you change the extractor. |
