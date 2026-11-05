# Herdr app personal layout

This layout combines the bundled workspace status with local quota and system load. The [mod sources](mods/) are the authority for behaviour; the [design record](../design/log/2026-11-05-herdr-app-orch-mod.org) records the accepted contract.

## Install and select

1. Initialise the dotagents submodule and apply the parent dotfiles Home Manager configuration using the normal host procedure. It links only `mods/herdr-orch`, `mods/system` and `layouts/herdr-orch.json` into `~/.config/herdr-app`; it rejects foreign occupants at those paths. It does not select a layout. Move any conflicting personal files yourself before applying.
2. In Herdr app, explicitly select the `herdr-orch` layout. The existing layout remains selected until you do this.

To remove the personal layout, select another layout in Herdr app first. Remove the three personal leaf declarations and `checkHerdrAppLeaves` guard from the parent `agents.nix`, then reapply the parent configuration using the normal host procedure. Do not remove app-owned directories or the source checkout.

The quota mod requires the home-installed `~/.agents/skills/herdr-orch/scripts/oh` and its runtime prerequisites. It runs `oh usage --format json` from your home directory and reports quota for this computer, not a selected remote machine. See the [usage contract](../skills/herdr-orch/scripts/docs/contract.md#usage) for provider setup and cache behaviour.

## Refresh and limits

Quota refresh starts on display, repeats every 60 seconds, and shares an in-flight request with the Refresh action. A failed request marks the last successful snapshot stale immediately; the popup shows its age and a safe failure category. After five minutes without a successful request, it shows unavailable instead. The app's native command limit is 30 seconds.

The system companion supports CPU and RAM on Linux and macOS; Linux GPU data requires NVIDIA. Missing GPU data does not suppress CPU and RAM. System load follows the selected machine; Activity Monitor is available only for the local macOS machine. Windows, AMD and Intel GPU collection are outside scope.

## Provenance and updates

The personal layout is based on the shipped `main` layout from herdr-app 0.1.10. The running app executable and the `herdr-app` command both reported 0.1.10 when the layout was copied. The copy does not receive upstream layout changes automatically: compare it with the new shipped `main` after an app upgrade, retain the three-item status row, and validate the result in the app.

The quota UI is adapted from [Lakris2407/herdr-mods quota](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/quota), and the system companion is vendored from [the system mod](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/system), both at revision `612d5e8fc8117db8a75f457b2f0ba48f50550626` under MIT. Their notices are in [quota LICENSE](mods/herdr-orch/LICENSE) and [system LICENSE](mods/system/LICENSE); [system provenance](mods/system/UPSTREAM.md) records its local change.

Run the portable offline suite with `bb scripts/test-herdr-app.bb`. To include the parent wiring integration checks, set the explicit path: `HERDR_APP_WIRING=../agents.nix bb scripts/test-herdr-app.bb`. An invalid path fails the integration checks.

When updating either upstream mod, compare the pinned revision with the new upstream source, preserve the local quota contract and local macOS action restriction, update provenance and licences as needed, and run `bb scripts/test-herdr-app.bb`. Offline tests do not replace native Linux and macOS validation, including restart and linked-source reload checks.
