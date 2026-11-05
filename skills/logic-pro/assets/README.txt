Logic MIDI Extractor
====================

This tool copies MIDI notes from a Logic Pro project into a .mid file. It does not change the Logic project. You do not need to install Python, Java or Babashka.

Before distribution: these packages need a test on each supported Mac processor, including the file chooser and macOS security prompts. The packages are unsigned and are not notarised by Apple. BUILD.txt identifies the source and bundled runtime.

Quick start (Mac)
-----------------
1. Choose the macos-aarch64 download for Apple Silicon, or macos-amd64 for an Intel Mac. Check About This Mac if you are not sure.
2. Unzip the download. Keep all its files in the same folder. If the Logic project is zipped, unzip that too.
3. Double-click Extract MIDI.command. It opens Terminal and a project chooser. Choose your .logicx project, not a file inside it.
4. Read the result in Terminal. On success, the .mid file is beside your .logicx project. Import it into your music application.
5. Press Return to finish.

If macOS blocks the launcher or executable, approve only a download from a source you trust. Follow Apple's instructions at https://support.apple.com/en-gb/102445. Do not disable Gatekeeper. Ask the person who supplied the tool if you are unsure.

Terminal commands (Mac and Linux)
--------------------------------
On Linux, use the linux-amd64-static package and run the commands below; the .command launcher is Mac-only. Open a terminal in the extracted tool folder. Paths with spaces need double quotes. You can drag a project from Finder into Terminal to insert its path.

  ./logic-midi --help
  ./logic-midi --version
  ./logic-midi "/Users/you/Music/My Song.logicx"
  ./logic-midi --bpm 120 "/Users/you/Music/My Song.logicx" "/Users/you/Music/Piano.mid"
  ./logic-midi --list "/Users/you/Music/My Song.logicx"

The tool reads the constant tempo directly from the project's binary or XML MetaData.plist on both Linux and macOS. No external plist utility is needed. --bpm is an optional override. If metadata is missing or invalid, use --bpm with the tempo shown in Logic, or ask the project author. A raw ProjectData input also needs --bpm.

If more than one alternative exists, the tool stops rather than guessing. Ask the project author which alternative to use, then add --alternative N (for example --alternative 0).

If the output already exists, choose another output name. The tool never replaces an existing file. If a write fails, a partial output file can remain; remove that output before you retry.

If you see "tempo-map drift", the supplied tempo can be wrong or the project can have tempo changes. The tool cannot export tempo changes. Do not increase --max-drift-ms to hide this error.

Send the complete error message and the output of --version to the person who supplied the tool if you need help. Exit status 0 means success, 1 means a file or extraction error, and 2 means incorrect arguments.

Known limits
------------
The project format is reverse-engineered and is not verified for every Logic version. Check the notes and timing after import. Output has 960 ticks per quarter note, constant tempo and a 4/4 time signature. Each sequence with notes becomes a MIDI track.

Notes before bar 1 are clamped to tick 0, keeping their original ends. Notes that finish at or before bar 1 are dropped. The tool reports the counts. Earlier pressure events move to tick 0.

Note-off velocity is always 64. Pressure events are exported as channel pressure, but this interpretation is not confirmed. Sustain, other controllers, pitch bend and program changes are not exported. Region transpose, velocity changes, quantise, looping and trimming are not applied.

Runtime and source
------------------
BABASHKA-LICENSE contains the Babashka licence. BUILD.txt links the exact runtime source release. logic_extract_midi.clj is the extractor source with the embedded revision used in this package. The self-contained executable combines that source's uberjar with the Babashka runtime.
