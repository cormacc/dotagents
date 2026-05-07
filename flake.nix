{
  description = "Agent skills and pi extensions: org-memory task-protocol bundle and friends";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs, ... }:
    let
      # Systems we ship pre-built outputs for. Linux + Darwin, both
      # architectures, so that a flake-input consumer on either platform
      # can pull `packages.<system>.agent-org-memory` directly.
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f:
        nixpkgs.lib.genAttrs systems
          (system: f (import nixpkgs { inherit system; config.allowUnfree = true; }));
    in
    {
      packages = forAllSystems (pkgs: rec {
        # Pi package + agent-skills bundle for the org-memory task
        # protocol. Build with:
        #   nix build .#agent-org-memory
        agent-org-memory = pkgs.callPackage ./agent-org-memory.nix { };
        default = agent-org-memory;
      });

      # Re-exposed as a flake check so `nix flake check` covers each
      # supported system's package output.
      checks = forAllSystems (pkgs: {
        agent-org-memory = pkgs.callPackage ./agent-org-memory.nix { };
      });
    };
}
