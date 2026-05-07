{
  emacsPackages,
  git,
  nodejs_20,
  nodePackages,
  pi ? warbo-packages.pi,
  runCommand,
  tsx,
  typescript ? nodePackages.typescript,
  warbo-packages,
}:
runCommand "pi-extension-emacsclient"
  {
    buildInputs = [
      (emacsPackages.emacsWithPackages (
        es:
        builtins.attrValues {
          inherit (es.treesit-grammars) with-all-grammars;
        }
      ))
      pi
      git
      typescript
      nodejs_20
      tsx
    ];
  }
  ''
    export HOME="$PWD"
    cp -r ${./.} "$out"
    chmod +w -R "$out"
    patchShebangs "$out"
    cd "$out"
    ./test.sh
  ''
