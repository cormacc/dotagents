# agent-org-memory: pi package + agent-skills bundle for the org-memory
# task protocol.
#
# Builds a filtered subtree of `./.` (the dotagents repo root) containing
# only the org-memory slice: the three packaged pi extensions, their
# helper modules, the three packaged generic skills, the pi package
# manifest, and the package README.
#
# Excluded: co-located test files (*.test.ts, test.sh), per-extension
# build wrappers (default.nix), unrelated extensions/skills (e.g.
# pi-clojure, term, lsp, ext-dev.ts, …), pi-specific skills, the
# disabled/ archive, owner-facing pi instructions (AGENTS.md), and
# prompt templates.

{ lib
, runCommand
}:

let
  root = ./.;

  # Predicate applied to every regular file under `root` before it can be
  # added to the package. Drops co-located test artefacts and per-extension
  # build wrappers regardless of where in the slice they live.
  isPackagedFile = file:
       !(lib.hasSuffix ".test.ts" file.name)
    && file.name != "test.sh"
    && file.name != "default.nix";

  # Slice = the explicit set of files/directories the package exposes.
  # Helper directory `pi/extensions/lib` is included so relative imports
  # from the three extensions resolve, but it is NOT listed in the
  # manifest's `pi.extensions` array, so pi's loader skips it.
  slice = lib.fileset.unions [
    (root + "/package.json")
    (root + "/README.md")
    (root + "/pi/extensions/tasks")
    (root + "/pi/extensions/jira")
    (root + "/pi/extensions/emacsclient")
    (root + "/pi/extensions/lib")
    (root + "/skills/org-tasks")
    (root + "/skills/org-plan")
    (root + "/skills/org-jira")
  ];

  src = lib.fileset.toSource {
    inherit root;
    fileset = lib.fileset.intersection
      slice
      (lib.fileset.fileFilter isPackagedFile root);
  };

  # Read version from package.json so the derivation name tracks the manifest.
  manifest = lib.importJSON (root + "/package.json");
in
runCommand "agent-org-memory-${manifest.version}"
  {
    inherit src;
    passthru = {
      inherit manifest;
      slice = src;
    };
    meta = with lib; {
      description = manifest.description;
      homepage = manifest.homepage;
      license = licenses.mit;
      platforms = platforms.all;
    };
  }
  ''
    mkdir -p "$out"
    cp -r "$src"/. "$out"/
    chmod -R u+w "$out"
  ''
