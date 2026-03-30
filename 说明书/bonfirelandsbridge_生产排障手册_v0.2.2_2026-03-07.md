# bonfirelandsbridge 生产排障手册 v0.2.2

位置: bonfirelandsbridge 项目内 说明书 目录
适用版本: v0.2.2
适用问题:
- Lands 续租按“剩余时间上限”判断
- 老租户时间久了以后无法 `/lands trust <居民名>`
- 居民没有站在自己租赁区域里就执行 trust

## 1. 一句话理解这个插件

这个桥接插件现在做三件事:

1. 接管 Lands 租赁牌的续租判断，改成按“当前剩余时间”判定。
2. 在续租时自动检查一次当前租户身份，缺了就补。
3. 在 `/lands trust <居民名>` 前自动检查一次当前租户身份；如果居民没站在自己的租赁区域里，就直接给中文提示。

## 2. v0.2.2 比 v0.2.1 多了什么

v0.2.1 已经加入了“租户身份自修复”。
v0.2.2 继续补了两层体验修复:

- `/lands trust <居民名>` 前，如果居民不在自己租的区域里，插件会直接拦下并提示中文。
- 插件自己的玩家提示、管理命令提示统一改成中文。
- trust 相关提示里统一使用“居民”这个词，减少理解偏差。

## 3. 玩家实际体感会怎样

正常玩家几乎无感:

- 续租还是原来的 Lands 租赁牌。
- trust 还是原来的 `/lands trust <居民名>`。
- 正常站在自己租区里操作时，玩法和以前一样。

唯一新增感知:

- 如果居民站错地方就执行 trust，现在会直接收到中文提示，要求先站回自己的租赁区域内。

## 4. 现在的核心逻辑

### 4.1 续租逻辑

1. 玩家右键 Lands 续租牌。
2. 插件读取当前 area 的租赁快照。
3. 用“当前剩余时间 + 本次租期 <= 原始上限”做判断。
4. 没超上限才允许扣费续租。
5. 续租后再检查 tenant / trusted / tenant 角色 是否完整。
6. 如果缺了，就先补回去，再保存到 Lands。

### 4.2 trust 逻辑

1. 居民执行 `/lands trust <居民名>`。
2. 插件先看居民脚下是不是他自己租的 area。
3. 如果是，就先做一次租户身份自修复，再把命令交还给 Lands。
4. 如果不是，但这个居民确实在别处租了房，而且他又不是领地主，那么插件会直接拦截并提示“请站回自己的租区里再 trust”。
5. 如果这个人本身是领地主，仍然尽量保持 Lands 原生命令行为，不做强拦。

## 5. 升级到 v0.2.2 时要注意什么

1. 只替换 `plugins/bonfirelandsbridge.jar` 即可。
2. 保留原有 `plugins/bonfirelandsbridge` 数据目录，不要删。
3. 特别不要删 `base-max-registry.yml`。
4. 不需要清空老玩家租赁数据，也不需要让全服重新退租重租。
5. 不需要新建一套新的 MySQL，也不需要额外加 Redis。
6. 配置基本兼容 0.2.1；这一版主要是逻辑补强和中文提示补全。

## 6. 线上排障时先看哪里

优先看这几个位置:

- `plugins/bonfirelandsbridge/config.yml`
- `logs/latest.log`
- `plugins/bonfirelandsbridge/base-max-registry.yml`
- `bridge-audit-runtime_YYYY-MM-DD.csv`

## 7. 快速判断思路

### 7.1 玩家不能续租

先查:

- `enabled` 是否为 `true`
- `dry-run` 是否还是 `true`
- `database.enabled` 是否为 `true`
- `runtime.intercept-rental-blocks` 是否为 `true`
- `latest.log` 里有没有 `bonfirelandsbridge` 报错

### 7.2 玩家能住，但不能 trust

先按这个顺序看:

1. 先确认居民是不是站在自己租的区域里。
2. 再看 `latest.log` 里有没有 `Tenant self-heal` 相关日志。
3. 如果日志显示修复成功，但 trust 仍失败，再回头排查 Lands 自己的角色与信任状态。
4. 如果日志显示修复失败，重点看当前 area 的 tenant 角色是不是异常漂移。

### 7.3 老租户住很久以后才不能 trust

这通常说明:

- 续租时间本身没问题。
- 出问题的是租户身份、trusted 状态或者 tenant 角色。
- v0.2.2 的目标就是尽量在“续租”和“trust 前”两次时机把它补回来。

## 8. 回滚原则

最稳的回滚方式:

1. 先把 `plugins/bonfirelandsbridge/config.yml` 里的 `enabled` 改成 `false`。
2. 重启服务器。
3. 如果还要继续回滚，再替换回旧版 jar。

不要先删数据目录。
特别不要先删 `base-max-registry.yml`。

## 9. 这一版最重要的一句话

v0.2.2 现在同时修三类问题:

- 续租时间判断不合理
- 老租户身份漂移导致 trust 失效
- 居民站错位置执行 trust 时没有明确提示

以后排障时，先分清到底是“时间没续上”，还是“租户身份没补上”，还是“人站错地方执行 trust”，方向就不会跑偏。