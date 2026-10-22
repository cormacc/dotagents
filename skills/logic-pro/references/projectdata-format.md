# Logic Pro ProjectData format

Apple does not document this format. This description comes from the reverse engineering of one project, saved by Logic Pro 11.2 (6306) and analysed on 2026-10-22. Each statement below was checked against that project only. Treat each one as a hypothesis for another Logic version, and check it with the survey and the tempo check before you trust the output.

## Project bundle

A `.logicx` project is a macOS package: a directory that Finder shows as one file. On Linux it is an ordinary directory. A zip made on macOS can also contain a `__MACOSX/` directory with resource forks; ignore it.

| Path in the bundle | Content |
| --- | --- |
| `Alternatives/NNN/ProjectData` | The binary project data: tracks, regions, MIDI events, mixer and plug-in state. `NNN` is the project alternative, from `000`. |
| `Alternatives/NNN/MetaData.plist` | `BeatsPerMinute`, `SongSignatureNumerator`, `SongSignatureDenominator`, `SongKey`, `SampleRate`, `NumberOfTracks`, and the media lists `AudioFiles` and `SamplerInstrumentsFiles`. |
| `Alternatives/NNN/DisplayState.plist`, `DisplayStateArchive`, `WindowImage.jpg` | Window and display state. `WindowImage.jpg` is a screenshot of the arrange window, which is useful to identify tracks and regions. |
| `Resources/ProjectInformation.plist` | `LastSavedFrom` (the Logic version) and `VariantNames` (the alternative names). |
| `Media/Audio Files/` | Audio recordings. In the analysed project, two files with a `#02` suffix were not RIFF WAV files and were not in `AudioFiles`. |

The plists are binary. Read them with `plutil -p <file>` on macOS, or with `python3 -c "import plistlib,sys; print(plistlib.load(open(sys.argv[1],'rb')))" <file>`.

## File and chunk structure

All integers are little-endian. The file starts with the magic bytes `23 47 c0 ab`. The first chunk starts at offset `0x18`. The chunks follow each other, with no gaps, to the end of the file: in the analysed project, the chunk walk ended exactly at the file length.

Each chunk has a 36-byte header, followed by its body:

| Offset | Size | Content |
| --- | --- | --- |
| 0 | 4 | Tag, with the characters in reverse order. For example, `qeSM` in the file is `MSeq`. |
| 4 | 24 | Type and ID fields. Not decoded. |
| 28 | 8 | Body length in bytes (u64). |

Tags seen: `Song` (first chunk, song settings), `MSeq`, `Trak`, `EvSq`, `InSt`, `TxSt`, `Trns`, `AuFl`, `AuRg`, `Styl`, `Layr`, `ScSt`, `Vide`, `AuCn`, `AuCO`, `AuCU`, `SngO`, `CorM`, `AFld`, `Hypr`, `Envi`, `GenM`.

- `MSeq` -- a sequence: a track, a folder, a region or an automation lane. The name length (u16) is at body offset 16, and the name starts at body offset 18.
- `Trak` -- data for the preceding `MSeq`. Not decoded.
- `EvSq` -- the event list of the preceding `MSeq`.
- `AuCU`, `AuCO`, `Envi`, `GenM` and the rest hold mixer, plug-in, environment and display state. Not decoded.

In the analysed project, 18 `EvSq` chunks were present. One (`MSeq` "Inst 1", 120256 bytes) held all 1866 notes. The others held the time signature (status `0x30`, `0x32`), other song data (`0x60`, `0x10`, `0x11`), automation and folder data (`0x20`, `0x24`), or only the terminator.

## Event records

An `EvSq` body is a list of 16-byte records. A record that starts an event has bit 7 of byte 7 clear. A continuation record has bit 7 of byte 7 set: `0x88`, `0x89`, `0x8a`, `0xa3`, `0xa4` and `0xbb` were seen. The list ends with a record that starts with `f1 00 00 00 ff ff ff 3f`.

Byte 0 of a start record is the event status. For MIDI events, it is the MIDI status byte: the high nibble is the type and the low nibble is the channel.

Positions are in ticks, with 960 ticks per quarter note. Bar 1 beat 1 is at tick 38400 (`0x9600`). Evidence: an `EvSq` in the song data starts with a `0x60` record whose position is `0x9600`, and the microsecond timestamps of the notes are zero at tick 38400.

### Note

A note is a group of four records (64 bytes). All 1866 notes in the analysed project had this exact group.

| Record | Byte 7 | Fields |
| --- | --- | --- |
| Note on | `0x00` | Byte 0: `0x90` + channel. Bytes 4..7: position in ticks (u32). Byte 11: velocity. Byte 12: pitch. |
| Note off | `0x89` | Byte 0: `0x80`. Bytes 12..15: length in ticks (u32). All other bytes were zero. |
| Time | `0xa4` | Bytes 0..3: position in microseconds from bar 1 (u32), at the project tempo. |
| Duration | `0xa3` | Bytes 0..3: length in microseconds (u32). |

Not decoded:

- Note-on bytes 9 and 10 vary between notes. Byte 9 was `0x00` or `0x80`. They are possibly a sub-tick offset.
- Note-on byte 1 was `0x40` and byte 15 was `0x01` for all notes.
- No note-off velocity is stored.

Example, the first note of the analysed project:

```text
90 40 00 00 90 9b 00 00 00 80 6d 5b 39 00 00 01   note on: tick 0x9b90 = 39824, velocity 0x5b = 91, pitch 0x39 = 57 (A3)
80 00 00 00 00 00 00 89 00 00 00 00 e7 00 00 00   note off: length 0xe7 = 231 ticks
18 51 0b 00 00 00 00 a4 00 00 00 00 00 00 00 00   time: 0x0b5118 = 741656 us
fe d5 01 00 00 00 00 a3 00 00 00 00 00 00 00 00   duration: 0x01d5fe = 120318 us
```

At 120 bpm one tick is 520.83 us. (39824 - 38400) x 520.83 = 741667 us, which agrees with the time record to 0.02 ms.

### Status 0xD0

A single start record, followed by one continuation record (`0xa4` for 21 events and `0xbb` for 3 events). Bytes 4..7 are the position. Byte 11 is a value from 0 to 127. Byte 10 also varies, and its meaning is not known. The values form ramps at 30-tick steps, so the events are possibly drawn or thinned controller data. The script exports them as MIDI channel pressure. This interpretation is not confirmed.

### Not seen

The analysed project had no controller (`0xb0`), pitch-bend (`0xe0`) or program-change (`0xc0`) events, no sustain pedal, and no tempo changes. Their layout is not known.

## Tempo check

The time record of each note gives an independent check of the tick-to-time conversion. At a constant tempo of B bpm, the time record must equal `(position - 38400) x 60000000 / (B x 960)` microseconds. `logic_extract_midi.bb` calculates the largest difference over all notes and fails when the difference exceeds `--max-drift-ms`. A large difference means that the tempo is not constant, or that `--bpm` does not match the project. In the analysed project, the largest difference was 0.02 ms at 120 bpm, and a run with `--bpm 121` gave 2148 ms.

## Not verified

- Region playback parameters (transpose, velocity offset, quantise, loop, region start and end trim). The script exports the events of each sequence as stored, so it does not apply them. Compare the result with `WindowImage.jpg` or with the user's knowledge of the project.
- Whether event positions are absolute song positions or relative to the region start. The region start in the analysed project was not decoded, so it does not distinguish the two.
- Projects with more than one MIDI region, or more than one alternative.
