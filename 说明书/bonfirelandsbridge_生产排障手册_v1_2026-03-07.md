# bonfirelandsbridge 生产排障手册 v0.2.1

位置: bonfirelandsbridge 项目内说明书目录  
适用范围: Lands 续租“剩余时间上限”桥接 + 租户身份自修复逻辑

## 1. 先用一句话理解这个插件

这个插件现在做两件事:

1. 修正 Lands 续租判断, 按“剩余时间上限”处理续租。
2. 在租户身份发生漂移时, 自动把当前租户的 trust / tenant 角色补回去。

所以 v0.2.1 不只是修续租, 也开始修“租久了之后无法 /lands trust”的问题。

## 2. v0.2.1 比 v0.2 多了什么

v0.2 主要解决的是:

- 玩家点续租牌子时
- 系统按剩余时间判断能不能继续加时

v0.2.1 额外新增两层“租户身份自修复”:

### 2.1 续租时自修复

玩家续租成功后, 插件会顺手检查:

- 当前玩家是不是这个 area 的 tenant
- 当前玩家是不是这个 area 的 trusted 成员
- 当前玩家当前 role 是否还能使用 `player_trust`

如果租户身份掉了, 插件会尝试补:

- `area.trustPlayer(player)`
- `area.setRole(player, tenant)`

然后再跟续租一起保存。

### 2.2 /lands trust 前自修复

玩家在自己租的区域里执行 `/lands trust <玩家>` 前, 插件会先检查当前脚下 area:

- 如果这里是玩家自己租的 area
- 且发现租户身份不完整

插件会先把当前租户身份补正, 再让 Lands 继续处理 trust 命令。

白话理解:

以前是“时间续上了, 但租客管理身份可能没续上”。
现在变成“只要玩家在自己租的地方继续续租或继续 trust, 插件都会先帮他补正身份再继续”。

## 3. 为什么这个修复是必要的

这个问题的典型表现是:

- 玩家刚租下来时可以 trust
- 住久一点后忽然不能 trust
- 但还能正常开门、交互、住在里面
- 一旦退租重租, trust 又恢复正常

这说明问题不是“房子没租上”, 而是“租户身份状态漂移了”。

v0.2.1 修的就是这个漂移。

## 4. 现在真实工作流

### 4.1 玩家续租时

1. 右键 Lands 续租牌子。
2. 插件读取当前 area 的租赁快照。
3. 按“当前剩余时间 + 本次续租时间 <= 原始上限”判断能否续租。
4. 扣费。
5. 直接修改 Lands 当前运行时的租期分钟数。
6. 检查 tenant 身份是否完整。
7. 如果 tenant 的 trust / role 掉了, 自动补回。
8. 调用 Lands 的保存流程落盘。

### 4.2 玩家 trust 其他人时

1. 玩家站在自己的租区里执行 `/lands trust <玩家>`。
2. 插件先检查脚下是否是玩家本人租的 area。
3. 如果是, 先检查 tenant 的 trusted / role 状态。
4. 如有缺失, 先补正。
5. 然后再让 Lands 原生 trust 命令继续执行。

## 5. 升级到 v0.2.1 时最重要的注意事项

### 5.1 不要删现网数据目录

升级时最稳的做法仍然是:

- 只替换 `plugins/bonfirelandsbridge.jar`
- 保留现有 `plugins/bonfirelandsbridge` 文件夹
- 特别是保留 `plugins/bonfirelandsbridge/base-max-registry.yml`

### 5.2 不需要重置玩家租赁数据

v0.2.1 的修复目标之一, 就是尽量避免玩家必须退租重租。

也就是说:

- 不需要清空历史租赁数据
- 不需要强制所有玩家重新租房
- 插件会在“续租”和“trust 命令前”主动尝试补状态

### 5.3 新配置项即使没写出来也会走默认值

v0.2.1 新增了两个 runtime 配置项:

- `runtime.repair-tenant-on-renewal`
- `runtime.repair-tenant-before-trust-command`

默认值都是 `true`。

所以就算你的生产环境老配置里暂时还没有这两个键, 插件也会按开启处理。

## 6. 线上最该看的几个文件

### 6.1 主配置

- `plugins/bonfirelandsbridge/config.yml`

### 6.2 原始上限注册表

- `plugins/bonfirelandsbridge/base-max-registry.yml`

### 6.3 运行时审计日志

- `plugins/bonfirelandsbridge/bridge-audit-runtime_YYYY-MM-DD.csv`

### 6.4 服务器主日志

- `logs/latest.log`

## 7. 关键配置项

### 必开项

- `enabled: true`
- `dry-run: false`
- `database.enabled: true`
- `runtime.intercept-rental-blocks: true`
- `runtime.repair-tenant-on-renewal: true`
- `runtime.repair-tenant-before-trust-command: true`

### 数据库仍然直接读 Lands 当前库

- `database.host`
- `database.port`
- `database.name`
- `database.user`
- `database.password`
- `database.table-prefix`

这些仍然必须指向 Lands 当前正在使用的 MySQL。

### Redis 相关

- `runtime.save-and-publish-to-redis: true`

如果 Lands 联服同步本来依赖 Redis, 就保持 `true`。
如果没有 Redis 联动, 可以改成 `false`, 让它只走 `land.save()`。

## 8. 现在真正有用的命令

### 查看状态

`/blb status`

重点看:

- `enabled`
- `dryRun`
- `runtimeReady`
- `economy`
- `snapshotRepo`
- `attempted/success/denied/failed`
- 最后一条 `lastRenewal`

### 手算验证

`/blb calc <baseMaxMinutes> <rentMinutes> <passedSeconds>`

作用:

- 只用来手算续租判定
- 不会真的改线上数据

### 旧命令说明

`/blb runonce` 和 `/blb restore` 在 v0.2+ 已经不是主逻辑。

## 9. 最常见问题怎么查

### 9.1 玩家还是不能续租

先查:

- `enabled` 是否为 `true`
- `dry-run` 是否还是 `true`
- `database.enabled` 是否为 `true`
- `runtime.intercept-rental-blocks` 是否为 `true`
- `latest.log` 里是否有 `bonfirelandsbridge` 报错

### 9.2 玩家还能住, 但不能 trust

这是 v0.2.1 重点修的情况。

排查顺序:

1. 让玩家站在自己租的 area 里执行 `/lands trust <玩家>`。
2. 看 `latest.log` 里是否出现 `Tenant self-heal` 相关日志。
3. 如果日志显示已修复, 但命令仍失败, 再继续查 Lands 自己的 role / trust 状态。
4. 如果日志显示修复失败, 重点看当前 area 的 tenant 角色是否异常。

### 9.3 trust 问题只在老租客身上出现

这通常意味着:

- 历史租赁数据里 tenant 状态发生过漂移
- 现在插件正在尝试补正

优先看:

- 玩家再次续租后是否恢复
- 玩家站在租区内直接 `/lands trust` 时是否恢复
- `latest.log` 里有没有 `Tenant self-heal repaired` 或 `Tenant self-heal failed`

### 9.4 扣费了但保存失败

优先看:

- `logs/latest.log`
- `bridge-audit-runtime_YYYY-MM-DD.csv`
- Lands 自己的保存异常或 Redis 异常

## 10. 推荐的线上排障顺序

如果生产环境再次有人反馈续租或 trust 异常, 最稳的顺序是:

1. 看 `logs/latest.log`。
2. 游戏内执行 `/blb status`。
3. 确认 `runtimeReady=true`。
4. 看最后一条 `lastRenewal` 的 `decision` 和 `note`。
5. 如果是 trust 问题, 看日志里是否出现 `Tenant self-heal`。
6. 再对照 `bridge-audit-runtime_YYYY-MM-DD.csv`。
7. 最后才去怀疑基础上限或历史租赁数据。

## 11. 回滚原则

最稳的回滚方式:

1. 先把 `plugins/bonfirelandsbridge/config.yml` 里的 `enabled` 改成 `false`。
2. 重启服务器。
3. 如果还要继续回滚, 再换回上一版 jar。

不要先删数据目录。  
特别不要先删 `base-max-registry.yml`。

## 12. 这份手册最重要的一句话

v0.2.1 现在修两类问题:

- 续租判定不合理
- 租户身份漂移导致的 trust 失效

以后排障时, 先分清是“时间没续上”还是“租户身份没补上”, 方向就不会跑偏。