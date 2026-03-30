# bonfirelandsbridge

![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Paper%201.21.8-brightgreen)
![Dependencies](https://img.shields.io/badge/dependencies-Lands%20%2B%20Vault-blueviolet)
![Status](https://img.shields.io/badge/status-active-success)

`bonfirelandsbridge` is the runtime bridge layer for Lands rental renewals, trust or untrust fixes, and player-facing rental query commands on the Bonfire network.

## Highlights

- Repairs renewal behavior around remaining time and tenant state transitions.
- Adds runtime self-heal logic before renewal, trust, and untrust actions.
- Exposes player and admin rental query flows through `/blb`.
- Keeps the bridge focused on runtime behavior instead of deployment packaging.

## Core Commands

- `/blb myrent`
- `/blb myrent detail`
- `/blb rentinfo <player>`
- `/blb rentlist [page]`
- `/blb status`
- `/blb reload`

## Build

```powershell
.\mvnw.cmd -q -DskipTests package
```

## Repository Scope

- Source, runtime config, and operator notes only.
- Local reverse-engineering workspaces and deployment bundles are excluded from Git.

## License

GPL-3.0
