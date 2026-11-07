# Upstream source

Derived from the `spaces` and `agents` mods bundled with herdr-app 0.1.10 (`herdr-desktop` 0.1.10), under the Apache License 2.0 in `LICENSE`.

- `main.js` starts from the bundled `spaces` mod (`main.js` SHA-256 `65f0f8bb48b8e9266266dc0adbd785c6d7372429db5ca9807cea2dfe0b48fefb`).
- The agent row, status mark and sidebar toggle come from the bundled `agents` mod (`main.js` SHA-256 `e4bafc03c6a4b77a8bd5330c36b1ec2c9e734d179f90c231aa859f143d21bda6`).

Local changes: each space row is followed by its agents and their live sub-agents as one tree (`tree.js`), named by the `oh.label` terminal label; the narrow rail shows spaces only and carries the control that widens the sidebar.
