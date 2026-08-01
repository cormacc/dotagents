---
name: nix
description: Edit Nix flakes, NixOS / Home Manager / nix-darwin modules, and per-project devShells. Use whenever the user mentions Nix, nixpkgs, flake.nix, flake.lock, configuration.nix, home.nix, home-manager, nix-darwin, devShell, mkDerivation, mkShell, direnv `use flake`, `--impure`, overlays, or edits any `.nix` file. Covers option-first refactors, eval-time validation, and pinning rules.
---

# Nix

Most Nix tasks fall into two buckets:

1. **System / user config** -- NixOS modules, Home Manager modules, nix-darwin modules. Lives in a dotfiles flake. Edits change running systems.
2. **Per-project build environment** -- `flake.nix` with `devShells`, consumed via `nix develop` or direnv `use flake`. Edits change what `cd $project` gives you.

Both are flakes. Both are evaluated before they run anything. The single biggest source of friction is preferring hand-rolled files (`home.file`, `writeShellScriptBin`, `extraConfig` strings, manual systemd unit copies) over first-class options. Default to the option; reach for the hand-roll only when no option exists.

Defer to the project's `AGENTS.md` and any per-flake notes for which hosts exist, which inputs are pinned, and which workflows (`--impure`, custom overlays) the repo expects. These rules govern Nix work only.

## Core workflow

Every Nix task is gather → change → eval-validate:

1. **Read the target file plus its imports.** Module layering matters: a change to `nixos-base.nix` affects every host that imports it. Use `rg` on the option name across the flake before adding it anywhere new.
2. **Find the option, don't invent it.** Before writing `home.file."…"` or `extraConfig = ''…''`, check whether the relevant `programs.*` / `services.*` / `home.*` / `targets.*` / `hardware.*` module already exposes what you need. Search nixpkgs source, `nix-doc`, `manix`, `home-manager option`, or just `nix search nixpkgs#…`.
3. **Make the smallest correct edit.**
4. **Eval-validate before claiming success.** See *Validation*. A Nix edit that parses is not necessarily one that evaluates; one that evaluates is not necessarily one that builds.
5. **Reload / rebuild only when asked.** `nixos-rebuild switch`, `home-manager switch`, `darwin-rebuild switch` are destructive; the `*-rebuild build` variants are not.

Ask for clarification when an edit changes user-visible state, when the option taxonomy is ambiguous (system vs. user, NixOS vs. nix-darwin), or when pinning a new input.

## Option-first refactor checklist

When tempted to write any of the following, look for a first-class option first:

| Hand-roll                                       | Prefer                                                   |
|-------------------------------------------------|----------------------------------------------------------|
| `home.file.".ssh/config".text = ''…''`          | `programs.ssh.matchBlocks` / `extraConfig`               |
| `home.file.".npmrc".text = ''…''`               | `programs.npm.npmrc` (HM ≥ 24.05)                        |
| `home.file.".gitconfig"…`                       | `programs.git.userName` / `userEmail` / `extraConfig`    |
| `writeShellScriptBin "foo" "…"` for an alias    | `home.shellAliases.foo = "…"`                            |
| Manual `systemd/user/foo.service` symlink       | `systemd.user.services.foo = { … };`                     |
| Literal `xkb` invocations in `extraSessionCommands` | `services.xserver.xkb` / `console.keyMap` / `home.keyboard` |
| Bespoke `<?xml … plist …?>` strings             | `lib.generators.toPlist {} { … }`                        |
| Hand-formatted ini / toml / json into a string  | `lib.generators.toINI` / `toTOML` / `builtins.toJSON`    |

If no option fits, hand-rolling is fine -- but add a comment naming the option you looked for so the next reviewer doesn't redo the search.

## System / user config rules

- **Boundary discipline.** Per-machine OS state goes in NixOS modules; per-user state goes in Home Manager. nix-darwin sits on the OS side for macOS. Don't put user dotfiles in `environment.etc`; don't put bootloader config in `home-manager`.
- **`mkOutOfStoreSymlink` for editable configs.** When a config file lives in the dotfiles repo and the user wants to edit it without a rebuild, prefer `config.lib.file.mkOutOfStoreSymlink` over `source = ./path` (which copies into the store and requires a switch per edit).
- **`home.activation` DAG ordering.** Use `lib.hm.dag.entryBefore [ "writeBoundary" ]` for sanity-check scripts that should fail before HM writes anything; `entryAfter [ "writeBoundary" ]` for scripts that need the symlinks in place. Don't mutate files HM itself manages.
- **Override priorities.** `lib.mkDefault` lowers priority; `lib.mkForce` raises it; `lib.mkBefore` / `lib.mkAfter` order list merges. When you hit *"option X has conflicting definition values"*, the answer is almost always one of these -- not a second `nix.settings.foo = …` line.
- **`nix.settings.*` replace vs. merge.** `substituters` and `trusted-public-keys` *replace* the upstream default. `extra-substituters` and `extra-trusted-public-keys` *merge*. Use the replacing form in a base module, the `extra-*` form in host overlays.
- **`--impure` is per-flake, not universal.** If a flake reads env vars (`builtins.getEnv "HOME"`, `NAME`, etc.) it requires `--impure`. Most third-party flakes don't and shouldn't.

## Per-project devShell rules

A minimal `flake.nix` for a project devShell:

```nix
{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [ … ];
          shellHook = ''…'';
        };
      });
}
```

- **`packages` vs `nativeBuildInputs` vs `buildInputs`.** In a `mkShell`, `packages` is the modern catch-all for things you want on `$PATH`. `nativeBuildInputs` is for build-time tools when the shell is also used to compile something cross-platform; `buildInputs` is for linkable libraries. For a pure-tooling devShell, just use `packages`.
- **direnv integration.** `.envrc` should be `use flake` (one line). Users need `programs.direnv.nix-direnv.enable = true` in HM for the cached fast path; without it every shell entry re-evaluates.
- **No floating fetches in devShells.** Don't `builtins.fetchGit` without a `rev` -- it re-fetches on every eval and breaks reproducibility. If you need an external source, make it a flake input (`flake.lock` pins it) or use `pkgs.fetchFromGitHub` with both `rev` and `hash`.
- **`mkShellNoCC` for shells that don't need a C compiler** (Python, JS, shell tooling). Slightly faster eval, smaller closure.

## Validation

Run the cheapest check that proves the edit. In order of cost:

| Check                                                          | When                                              |
|----------------------------------------------------------------|---------------------------------------------------|
| `nix eval --impure .#nixosConfigurations.<host>.config.<opt>`  | Confirm an option actually carries the value you expect (replace `.<host>` with `darwinConfigurations.<name>` or `homeConfigurations.<name>` as needed). |
| `nix eval --impure .#nixosConfigurations.<host>.config.system.build.toplevel.drvPath` | Confirm the host evaluates to a derivation (no build). |
| `nix flake check --impure --no-build`                          | Eval every output. Useful before commit; noisy on flakes with dead/legacy configs. |
| `nixos-rebuild build --flake .#host --impure`                  | Realise the derivation locally without activating. |
| `home-manager build --flake .#name --impure`                   | Same for HM.                                      |
| `darwin-rebuild build --flake .#host --impure`                 | Same for nix-darwin.                              |
| `nix develop` / `direnv reload`                                | Smoke-test a devShell.                            |
| `*-rebuild switch …`                                           | Actually activate. Destructive -- only on request. |

Every `.#` above resolves against the *current* flake. When the edit targets a flake outside the current repository -- for example a submodule editing its parent's config -- `cd` to that flake root first; evaluating from the subdirectory silently evaluates the wrong flake:

```
cd <target-flake-root> && nix eval --impure .#homeConfigurations.<name>.activationPackage.drvPath --raw
```

`nix repl` is useful for iterative exploration:

```
nix-repl> :lf .                              # load this flake
nix-repl> nixosConfigurations.strix.config.nix.settings.substituters
nix-repl> :p homeConfigurations.default.config.programs.ssh.matchBlocks
```

For dirty-working-tree evals, `git add` new files first -- Nix copies only tracked files into the store, and `path does not exist` errors point at an untracked `.nix` file.

## Pinning and inputs

- **Pin everything.** Floating `builtins.fetchGit` / `fetchTarball` without `rev` + `narHash` is reproducibility debt. Convert to a flake input (which `flake.lock` pins automatically) or to `pkgs.fetchFromGitHub` with a `hash`.
- **`inputs.foo.inputs.nixpkgs.follows = "nixpkgs"`** collapses a transitive nixpkgs to the parent's, shrinking closure and eval time. **Don't add it** for inputs whose authors deliberately pin nixpkgs to match a binary cache (e.g. anything serving a cachix.org with closure hashes). When in doubt, leave the input's own pin alone and accept the extra eval.
- **`flake.lock` is source.** Don't `nix flake update` casually -- every bump is a potentially-breaking change. Update one input at a time (`nix flake lock --update-input foo`) when you have a reason.

## Reporting changes

Cite code as `file_path:line_number`. When proposing a refactor, say which option you'd use and link to its docs (`home-manager option …`, nixpkgs source path) before writing code. When evaluating, paste the exact `nix eval` or `*-rebuild build` invocation used so the user can re-run it.
