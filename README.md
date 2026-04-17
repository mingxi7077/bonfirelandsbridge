# bonfirelandsbridge

[English](#english) | [简体中文](#简体中文)

bonfirelandsbridge is a Bonfire runtime bridge for Lands rental and trust flows.

bonfirelandsbridge 是 Bonfire 面向 Lands 租赁与信任流程的运行时桥接插件。

---

## English

`bonfirelandsbridge` is a runtime bridge layer for Lands rental renewals, tenant trust or untrust fixes, and player-facing rental query flows on the Bonfire network.

### What It Does

- Repairs renewal behavior around remaining time and tenant state transitions.
- Adds runtime self-heal logic before renewal, trust, and untrust actions.
- Exposes player and admin rental query flows through `/blb`.
- Focuses on runtime behavior rather than packaging-only integration.

### Core Commands

- `/blb myrent`
- `/blb myrent detail`
- `/blb rentinfo <player>`
- `/blb rentlist [page]`
- `/blb status`
- `/blb reload`

### Repository Layout

- `src/`: plugin source code
- `tools/`: local helper tooling
- `说明书/`: local operator notes
- `部署包/`: local deployment workspace

### Build

```powershell
.\mvnw.cmd -q -DskipTests package
```

### License

This repository currently uses the `Bonfire Non-Commercial Source License 1.0`.
See [LICENSE](LICENSE) for the exact terms.

---

## 简体中文

`bonfirelandsbridge` 是 Bonfire 网络中用于处理 Lands 租赁续租、租客信任修复以及玩家查询流程的运行时桥接层。

### 它的作用

- 修复续租时长计算与租客状态切换中的行为问题。
- 在续租、信任、取消信任前加入运行时自愈逻辑。
- 通过 `/blb` 对外提供玩家端与管理端的租赁查询能力。
- 聚焦运行时桥接行为，而不是单纯的部署打包。

### 主要命令

- `/blb myrent`
- `/blb myrent detail`
- `/blb rentinfo <player>`
- `/blb rentlist [page]`
- `/blb status`
- `/blb reload`

### 仓库结构

- `src/`：插件源码
- `tools/`：本地辅助工具
- `说明书/`：本地运维说明
- `部署包/`：本地部署工作区

### 构建方式

```powershell
.\mvnw.cmd -q -DskipTests package
```

### 授权

本仓库当前采用 `Bonfire Non-Commercial Source License 1.0`。
具体条款见 [LICENSE](LICENSE)。
