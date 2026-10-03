# 全工程架构解耦与超限类重构实施方案 (第二阶段)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据项目级《代码工程与交付红线（Q宠伴侣定制版）》，对工程中剩余的 3 个历史超限大文件（`HookEntry.kt` 498行、`QQPetDirectBridge.kt` 1854行、`QQSettingDialog.kt` 3142行）进行彻底的单一职责解耦与分层下沉，达成全工程所有文件严格 ≤ 400 行、单函数 ≤ 50 行的工程目标，同时保持对外 API、UI 展现和现有全部单元测试 100% 向前兼容。

**Architecture:** 
1. `HookEntry.kt`：提取 IPC 广播接收与配置分发至 `hook/ipc/EngineActionReceiver.kt`，入口仅保留 Xposed 钩子生命周期挂接。
2. `QQPetDirectBridge.kt`：提取底层反射发包通道（`OidbChannel`）与按业务分治的协议客户端（`PetCareProtocolClient`、`PetCareerProtocolClient`、`PetSocialProtocolClient`、`PetPkProtocolClient`），主 Bridge 演进为纯门面（Facade）。
3. `QQSettingDialog.kt`：下沉独立通用控件（`AppleSwitchView`、`AppleSegmentedControl`）、独立弹窗（`HireFriendWhitelistDialog`、`PkBlacklistDialog`）与独立设置卡片构建器（`Setting*Section`），宿主弹窗仅负责视图挂载与生命周期管理。

**Tech Stack:** Kotlin, Android View (Pure Code UI), LSPosed/Xposed API 82, trpc/PB ProtoWire, Android Local Unit Tests.

**Spec:** `AGENTS.md` (《代码工程与交付红线（Q宠伴侣定制版）》)

## Global Constraints

- 单文件严格 ≤ 400 行，超限必须拆分，严禁用「暂时保留」为借口。
- 单函数严格 ≤ 50 行，散参 ≤ 4 个（超过必须封装领域 Data Class），嵌套 ≤ 3 层。
- 依赖方向严格单向：界面 UI → 业务调度 Task → 协议通信 Client → 基础设施 Bridge/Channel，严禁反向逆调用。
- 仅重构代码物理结构与职责分布，对外展现、广播 Action/Extra、内部状态流转与单测断言 100% 保持向前兼容。
- 底层异常与反射调用禁止静默吞掉，同时坚决严禁未捕获异常冒泡至 QQ 宿主线程导致闪退。

## Review Focus

- `HookEntry` 拆分后跨进程广播权限与接收器注册时机是否与旧逻辑完全一致（包含 Android 13+ `RECEIVER_EXPORTED` 兼容）。
- `QQPetDirectBridge` 拆分后各 Client 单例或委托调用是否复用相同的 `ClassLoader` 与动态补链反射缓存。
- `QQSettingDialog` 拆分后的卡片组件与配置持久化是否在 UI 线程与主线程 Handler 正常调度，避免跨线程更新崩溃。
- 全量 29 个单元测试是否在每次拆分提交后保持 100% 通过。
- 拆分后各文件行数是否全部真实压降在 400 行红线以内。

---

### Task 1: 拆解 `HookEntry.kt` (498 行 -> < 300 行)

**Files:**
- Create: `app/src/main/java/com/copilot/qqpet/hook/ipc/EngineActionReceiver.kt`
- Modify: `app/src/main/java/com/copilot/qqpet/HookEntry.kt`

**Interfaces:**
- Consumes: `HookEntry.globalEngine`, `HookEntry.globalBridge`, `HookEntry.latestClassLoader`, `WakeLockHelper`
- Produces: `EngineActionReceiver.register(context: Context)`, `EngineActionReceiver.unregister()`

- [ ] **Step 1: 创建 `EngineActionReceiver.kt` 封装跨进程广播接收与配置持久化**
  将 `HookEntry.kt` 中冗长的 `adventureReceiver` 逻辑独立为轻量单例/类，单函数严格 ≤ 50 行。

- [ ] **Step 2: 修改 `HookEntry.kt` 委托调用 `EngineActionReceiver`**
  精简 `HookEntry.kt`，仅保留进程过滤、`TinkerBlocker` 与 `BaseApplicationImpl.onCreate` 反射挂钩。

- [ ] **Step 3: 运行单元测试验证编译与回归**
  运行：`./gradlew testReleaseUnitTest`
  预期：PASS (29/29)

- [ ] **Step 4: 检查行数达标**
  运行：`wc -l app/src/main/java/com/copilot/qqpet/HookEntry.kt app/src/main/java/com/copilot/qqpet/hook/ipc/EngineActionReceiver.kt`
  预期：两个文件均 ≤ 300 行。

---

### Task 2: 拆解 `QQPetDirectBridge.kt` (1,854 行 -> < 300 行门面)

**Files:**
- Create: `app/src/main/java/com/copilot/qqpet/protocol/channel/OidbChannel.kt`
- Create: `app/src/main/java/com/copilot/qqpet/protocol/client/PetCareProtocolClient.kt`
- Create: `app/src/main/java/com/copilot/qqpet/protocol/client/PetCareerProtocolClient.kt`
- Create: `app/src/main/java/com/copilot/qqpet/protocol/client/PetSocialProtocolClient.kt`
- Create: `app/src/main/java/com/copilot/qqpet/protocol/client/PetPkProtocolClient.kt`
- Modify: `app/src/main/java/com/copilot/qqpet/protocol/QQPetDirectBridge.kt`

**Interfaces:**
- Consumes: `OidbChannel.sendOidb(cmd, serviceType, subCmd, body, callback)`
- Produces: `QQPetDirectBridge` 对外公开方法保持 100% 不变，全部委托给专职子 Client。

- [ ] **Step 1: 下沉底层反射通道 `OidbChannel.kt`**
  封装 `sendOidb`、ClassLoader 动态推断与动态补链逻辑。

- [ ] **Step 2: 建立领域协议客户端 `Pet*ProtocolClient`**
  分别承载进食/沐浴/属性拉取（Care）、学业/打工/探险/地图（Career）、好友/点赞/福袋（Social）、PK竞技（Pk）。

- [ ] **Step 3: `QQPetDirectBridge.kt` 改造为纯门面委托**
  保留现有全部公共函数签名，原样转发至各领域 Client，保持完全兼容。

- [ ] **Step 4: 运行单元测试验证编译与回归**
  运行：`./gradlew testReleaseUnitTest`
  预期：PASS (29/29)

- [ ] **Step 5: 检查协议分层文件行数**
  运行：`wc -l app/src/main/java/com/copilot/qqpet/protocol/QQPetDirectBridge.kt app/src/main/java/com/copilot/qqpet/protocol/*/*.kt`
  预期：所有生成文件严格 ≤ 400 行。

---

### Task 3: 拆解 `QQSettingDialog.kt` (3,142 行 -> < 400 行宿主)

**Files:**
- Create: `app/src/main/java/com/copilot/qqpet/ui/component/AppleSwitchView.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/component/AppleSegmentedControl.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/theme/ThemeColors.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/dialog/HireFriendWhitelistDialog.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/dialog/PkBlacklistDialog.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingStatusCard.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingGeneralSwitchesCard.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingCareerCard.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingCareCard.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingSocialPkCard.kt`
- Create: `app/src/main/java/com/copilot/qqpet/ui/section/SettingActionGridCard.kt`
- Modify: `app/src/main/java/com/copilot/qqpet/ui/QQSettingDialog.kt`

**Interfaces:**
- Consumes: `ThemeColors`, `AppleSwitchView`, `AppleSegmentedControl`, 各 `Setting*Card.build(...)`
- Produces: `QQSettingDialog.show(activity: Activity, engine: PetAdventureEngine?)`

- [ ] **Step 1: 提取独立通用控件与主题包**
  `AppleSwitchView.kt`、`AppleSegmentedControl.kt`、`ThemeColors.kt` 独立成基础 UI 组件。

- [ ] **Step 2: 提取独立弹窗模块**
  `HireFriendWhitelistDialog.kt`（好友白名单勾选与搜索）、`PkBlacklistDialog.kt`（PK 黑名单管理）。

- [ ] **Step 3: 提取卡片组件构建器**
  将状态栏、全局总控、学业打工、自理阈值、社交PK、即时指令按卡片独立封装。

- [ ] **Step 4: `QQSettingDialog.kt` 精简为纯挂载容器**
  仅负责 BottomSheet 弹窗构建、主题背景应用与卡片添加，单文件控制在 250~350 行。

- [ ] **Step 5: 运行单元测试与全量编译打包**
  运行：`./gradlew testReleaseUnitTest && ./gradlew assembleRelease`
  预期：全部通过，产出有效 APK。

- [ ] **Step 6: 全工程行数全量扫描核验**
  运行：`find app/src/main/java -name "*.kt" -exec wc -l {} + | sort -nr`
  预期：全工程所有 Kotlin 文件单文件严格 ≤ 400 行，超限项彻底清零。
