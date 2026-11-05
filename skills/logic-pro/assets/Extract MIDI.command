#!/bin/bash
# Keep the result visible when Finder opens a temporary Terminal window.
base="$(cd "$(dirname "$0")" && pwd)" || exit 1
printf '%s\n' 'Logic MIDI Extractor' 'Choose an unzipped .logicx project in the next window.'
"$base/logic-midi" --version
project="$(/usr/bin/osascript <<'APPLESCRIPT'
try
  set projectFile to choose file with prompt "Choose a Logic Pro .logicx project" of type {"logicx"}
  return POSIX path of projectFile
on error number -128
  return ""
end try
APPLESCRIPT
)"
status=$?
if [ "$status" -eq 0 ] && [ -n "$project" ]; then
  "$base/logic-midi" -- "$project"
  status=$?
  if [ "$status" -eq 0 ]; then
    printf '%s\n' 'Finished. The MIDI file is beside your Logic project.'
  else
    printf '%s\n' 'Extraction failed. See the message above and README.txt for help.'
  fi
fi
printf '%s' 'Press Return to close this launcher. '
IFS= read -r reply
exit "$status"
