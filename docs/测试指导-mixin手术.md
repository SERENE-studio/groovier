# 测试指导:mixin 手术通道(§10.1)

> 随机制实现维护;机制背景见 `SPEC.md` §10.1 / §6.5 / §6.6。本文件是实机验证的执行手册,测试后请回填"结果"列。

## 1. 前置条件

| 项 | 要求 |
|---|---|
| jar 形态 | `build/libs/groovier-<ver>.jar`(完整 jar,coremod + mod 双身份)放 `run/mods` |
| dev 环境限制 | sourceSet 直跑**不激活** coremod 通道(minecraft jar 不含 services 声明);需 `coremodDevJar` 产物另放 `run/mods`(dev 测试时与 sourceSet mod 并存) |
| 构建注意 | 游戏运行时锁 `build/moddev/artifacts/*.jar`,构建前关闭客户端;`GRADLE_USER_HOME` 重定向 `D:\bluesky_fantaste\.gradle-home` |
| 产物清理 | 首测前清空 `run/local/mixin_invalidated/`(旧版本 scope 标注残留) |

## 2. 测试环境(run/mods 现状)

| mod | mixin 密度 | 行为可见性 | 标的评估 |
|---|---|---|---|
| [崩溃优化] notenoughcrashes | 中 | **高**:崩溃接管界面是显性行为 | **阳性对照首选**:类级摘除其 mixin 后,崩溃不再被兜底界面接管 |
| Paxi | 中 | 中:数据包/结构加载优先级 | 手术级标的候选 |
| YungsApi | 高 | 低(纯库,单独装行为不显) | mixin 数量多,**类级全摘/前缀展开**的压力测试标的 |
| KubeJS + Rhino | 中-高 | 中:脚本行为依赖其注入 | 不可乱摘(影响脚本引擎),仅作 postwatch 观测 |
| Create / Aeronautics | 中 | 低-中 | 手术级标的候选(具体类名从 jar 内 `*.mixins.json` 枚举) |
| Croptopia / EpheroLib / Sable | 低 | 低 | 后备 |

标的 mixin 类名的获取方法:解压目标 jar → `*.mixins.json` 的 `"package"` + `"mixins"`/`"client"` 数组拼接;或启动日志搜 `Mixin apply`/`mixin.config`。

## 3. 配置样例

`run/config/groovier-mixin-blacklist.txt`:

```text
# 类级:全摘(该类上一切 mixin)
org.yungsss.some.TargetClass

# 手术:仅摘指定 mixin,同 target 其余保留
net.minecraft.client.renderer.GameRenderer::com.example.BadMixin

# 前缀(类级/手术均支持,扫 mods 目录 jar 展开)
org.yungsss.*
```

`run/config/groovier-postwatch.txt`:

```text
# 观测类:pre/post 成对落盘 local/mixin_invalidated/watch/
# 通常与手术/类级规则的 target 一致,用于对照"残局"
net.minecraft.client.renderer.GameRenderer
```

`run/config/groovier-refer.txt`(6.5 反编译导出 targets):

```text
# 残局捕获类:mixin 后最终字节落盘 local/refer/classes/,后台反编译 → groovy_scripts/refer/
# 语法同 postwatch:精确类名或包前缀 prefix.*
net.minecraft.world.entity.monster.Zombie
com.example.some.*
```

## 4. 用例矩阵

| # | 用例 | 步骤 | 判定 | 观测点 | 结果 |
|---|---|---|---|---|---|
| T0 | 通道就绪 | 空 config 启动 | 日志出现 `Groovier mixin invalidation channel ready`;无 config 时提示 disabled | 启动日志 | |
| T1 | coprocessor 注入 | postwatch 写 1 个类,启动 | 日志 `post-mixin coprocessor injected (residual bytecode channel live)`;失败为 `injection failed permanently`(记录原因) | 启动日志 | |
| T2 | 残局产物成对 | T1 后进入存档(触发类加载) | `watch/pre/<类>.class` 与 `watch/post/<类>.class` **同时存在且不同**(若该类被 mixin 改过) | 文件系统 + 反编译对比 | |
| T3 | 类级全摘 | blacklists 写一个明确被 mixin 的类 | 报告 `removedMixins` = 该类全部 mixin;启动无报错;相关功能失效(预期) | `local/mixin_invalidated/report.json` | |
| T4 | 手术级摘除 | 改写 `target::mixin` 精确规则 | 报告 `removedMixins` 仅命中 1 项,`keptMixins` 列出保留项;被保留 mixin 的行为仍生效 | report.json + `/gvr mixin` | |
| T5 | `/gvr mixin` 一致性 | OP 执行 `/gvr mixin` | 显示规则/removed/`+ kept:` 与 report.json 一致 | 聊天栏 | |
| T6 | 前缀展开 | `org.yungsss.*` | 日志 `blacklist loaded: N rule(s) -> M target class(es)`,M = jar 内匹配类数 | 启动日志 | |
| T7 | fail-safe(通道破坏) | 临时改 mixinMapping 字段名模拟失败或观察真实失败 | 报告该类 `status: channel_failed`;游戏正常启动;mixin 照常应用 | report.json + 启动日志 | |
| T8 | 阳性对照(行为可见) | 类级摘除 notenoughcrashes 的 mixin → 触发一次崩溃 | 崩溃**不再**被 NEC 界面接管(普通崩溃报告) | 实机行为 | |
| T9 | 阴性对照 | 黑名单为空启动 | 无摘除行为,report.json 不存在或无 invalidations | 文件系统 | |
| T10 | 性能回归 | T6(大前缀展开)+ postwatch 若干类,启动耗时对比基线 | 无明显劣化(前缀展开仅启动期一次;coprocessor 对非观测类 O(1) 返回) | 启动日志耗时 |
| T11 | 正常脚本回归 | 现有 groovy_scripts 正常运行 | 无回归 | 实机 |
| T12 | 手术包提交 | 目标类先配入 blacklist/postwatch 启动一次 → 脚本 `Surgery.pre(target)` 拿 ClassNode → `Surgery.submit(name:..., target:..., drop:[...]) { node -> ... }`(先做无害改动,如给常量方法返回值 +0) | submit 返回摘要(preSha256/location);`local/surgeries/<name>/` 生成 surgery.txt + patch.class | 脚本输出 + 文件系统 |
| T13 | 手术包生效 | 携带 T12 手术包重启 | 日志 `surgery pack <name> applied to <target>`;补丁行为可见;`/gvr surgery` 显示 applied;`removedMixins` 仅 drop 命中项 | 日志 + `/gvr surgery` + 行为 |
| T14 | 手术包锚定失效 | 删除/替换目标类所在 mod(或换 mc 版本)重启 | 日志 `surgery pack <name> stale (pre sha mismatch)`;补丁不应用,游戏正常;`/gvr surgery` 显示 stale | 日志 + `/gvr surgery` |

### 手术包脚本样例(T12 参考)

```groovy
def target = "com.example.Foo"   // 需已配入 blacklist/postwatch 并启动过一次
def node = Surgery.pre(target)   // ClassNode(原身)
if (node == null) { Log.warn("no pre capture for {}", target); return }

Surgery.submit(name: "fix-foo", target: target,
    mode: "patch_with_mixins",                 // 默认;exclusive = 全摘
    drop: ["com.conflictmixin.BadMixin"]) { n ->
    // 示例:把某方法体改为直接 return 常量(方法体指令重写)
    n.methods.findAll { it.name == "isBroken" }.each { m ->
        m.instructions.clear()
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0))
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN))
    }
}
Log.info("submitted: {}", Surgery.list())
```

## 5. 日志锚点速查

| 锚点 | 含义 |
|---|---|
| `Groovier mixin invalidation channel ready` | 摘除反射链就绪 |
| `channel unavailable (mixin internals not found)` | 反射链断裂(0.15.2 之外版本) |
| `blacklist loaded: ... (N class-level, M surgical)` | 规则解析成功 |
| `post-mixin coprocessor injected` | 残局通道就绪 |
| `coprocessor injection failed permanently` | 注入失败(记录堆栈原因,通道禁用但不崩游戏) |
| `surgically invalidated N mixin(s) ... (M kept)` | 手术摘除执行 |
| `could not invalidate mixins on ...` | 通道失败(fail-safe) |

## 6. 已知边界(预期现象,非缺陷)

- **被摘除类不产生 post 产物**:类级全摘后该类不经 mixin 应用,`watch/post` 可能缺失——此时最终形态 = pre 原身;
- **dev 环境** coremod 通道不随 sourceSet 注入(见 §1);
- 手术规则命中"该类本无 mixin"时 removed/kept 均空,报告记为 `no mixin was targeting this class`;
- `defineHookClass` 两条定义路径(primary `privateLookupIn` / fallback `ClassLoader.defineClass`)哪条生效以 debug 日志为准——首测请留意回填。

## 7. 回填区

**测试日期 / 环境**:2026-08-29,dev runClient(NeoForge 21.1.235 / MC 1.21.1),mods 见 §2。

**T0-T13 通过情况**:

| # | 结果 | 实测锚点 |
|---|---|---|
| T0 | ✓ | `Groovier mixin invalidation channel ready` |
| T1 | ✓ | `post-mixin coprocessor injected (residual bytecode channel live)` |
| T2 | ✓ | watch/pre vs watch/post 成对:`Minecraft.class` 147368→165985(post≠pre 正例,NEC MixinMinecraftClient);`CrashReport`/`Entity` post=pre(符合 §6 边界:被摘除类无 mixin 应用) |
| T3 | ✓ | 类级 `Entity`:`invalidated 22 mixin(s) on net.minecraft.world.entity.Entity`(pool-21-thread-1,异步加载期) |
| T4 | ✓ | 手术级 `CrashReport::MixinCrashReport`:`invalidated 1 mixin(s)`(首轮);行为可见实证见 T8 变体 |
| T5 | — | 未单独执行 `/gvr mixin`(report.json 与日志一致,间接验证) |
| T6 | — | 未测前缀展开 |
| T7 | — | 未测通道破坏 |
| T8 | ✓(变体) | 类级摘除 `Entity` 后,存量实体 tick 触发 Sable `ClassCastException`(Skeleton/Zombie → `EntityMovementExtension`):**摘除真实生效的实证**(Sable 经 mixinterface 注入 Entity 的接口随 mixin 一并被摘,硬 cast 无防御)。与 NEC 接管场景不同源但同性质 |
| T9 | — | (先前的空 config 轮次已验证 disabled 分支) |
| T10 | — | 未做专项对比;coprocessor 在线时启动耗时无可感知劣化 |
| T11 | ✓ | 12 脚本正常执行,12 listeners;仅 `test_sandbox.groovy` 预期失败(沙箱拦截用例) |
| T12 | ✓ | 脚本 `Surgery.submit` 成功:`preSha256=a58c01dd…7e58,patchBytes=9050,location=local\surgeries\crashreport-marker`;surgery.txt(行式 manifest)+ patch.class 落盘;二次进入存档幂等 skip ✓ |
| T13 | ✓ | `surgery pack crashreport-marker applied to net.minecraft.CrashReport (patch_with_mixins mode)`(bootstrap + 二次类加载各一次);T13 脚本反射验证 `groovierSurgeryMarker present = true`;两段式闭环打通 |
| T14 | ✓ | 手改 preSha256 末位 → 重启:`surgery pack crashreport-marker stale (pre sha mismatch: recorded a58c01dd…7e58, actual a58c01dd…7e59)` 不应用;`/gvr surgery` 显示 stale;清理后可重新 submit |

**defineHookClass 实际生效路径**:注入成功(通道 live),debug 行未逐轮回填,默认 primary 路径生效。

**偏离与修复记录**:

1. **脚本执行时机 = ServerStarting(进入存档)**:主菜单不执行任何脚本;T12/T13 观测必须进入存档后才出现在 groovier.log。

2. **类级摘除的连带破坏(预期风险实证)**:摘除 `Entity` 会使依赖其 mixinterface 注入的 mod(Sable)硬 cast 崩溃——实测两轮 server crash(15:09 Skeleton / 15:12 Zombie)。非 Groovier 缺陷,已从测试配置移除类级 Entity 规则;生产配置应避免对"被其他 mod 硬依赖注入"的基类做类级摘除。
3. **手术级摘除时序**:首轮 `invalidated 1 mixin(s)` 出现在二次类加载期(15:10 轮因手术包应用接管同路径,未见独立行);摘除结果以 report.json 为准。
4. **偶发崩溃(待观察)**:14:38 `FMLConfig$ConfigValue.getConfigValue` NPE(earlydisplay 读 fml.toml 为 null),后续未复发,疑似 NEC 崩溃恢复写坏 fml.toml 的偶发,与 Groovier 无关。

## 8. 方法钉子用例(6.3 原身通路,2026-08-29 交付)

机制:核心侧(PinStore + PinningTransformer)在类首次加载(mixin 前)对 `local/pins/<name>/pin.txt` 声明的目标方法做**重命名包装**——原方法改名 `groovier$orig$<name>`,同名包装方法在入口调用 `GroovierHooks.enter/exit` 查询点,未命中透明放行。运行期脚本经 `Pins.override/on` 注册回调。

| # | 用例 | 操作 | 预期 |
|---|---|---|---|
| T15 | 声明钉子 | 脚本 `Pins.declare(name: "test-pin", target: "<某 mod 类>", method: "<方法名>")` 后 `/gvr reload` | `Pin pack 'test-pin' declared ... next launch` 日志;`local/pins/test-pin/pin.txt` 落盘;`/gvr pins` 显示 `not applied this launch` |
| T16 | 冷启动注入 | 重启客户端进入存档 | 启动日志 `Groovier pinned 1 method(s) on <target>`;`/gvr pins` 显示 `applied (n method(s))`;report.json `pins` 块 |
| T17 | override 生效 | T16 前脚本同文件 `Pins.override("<target>.<method>") { thiz, args -> Log.info("pin hit"); <返回值> }` | 调用目标方法时日志 `pin hit`,原方法体不执行(返回值被覆盖);`/gvr pins` 显示 `callbacks: enter=1` |
| T18 | on 伪事件 | 脚本 `Pins.on("<target>.<method>") { thiz, args, result -> Log.info("after") }`,override 未注册或返回 null | 原方法执行后日志 `after`,返回值不被篡改 |
| T19 | 未命中透明 | T17 脚本禁用(`/gvr disable`) | 目标方法恢复原行为(覆盖表为空,enter 查空放行),无异常 |
| T20 | fail-safe | pin.txt 写入不存在的方法名 → 重启 | 日志 `matched no method`,report 标 `error (no matching method ...)`,类正常加载 |
| T21 | 命令管理 | `/gvr pins remove test-pin` → 重启 | 清单消失;类不再注入 |

注意:
- 目标类必须**冷启动时未加载**才注入;若类已在本轮加载(如 MC 核心类),declare 只对下轮生效;
- `<init>`/native/abstract/synthetic 不钉(机制边界);重载方法不写 descriptor 时全部钉住、共用 key;
- void 方法覆盖约定:闭包返回任意非 null 值即"直接返回"(包装侧丢弃返回值),返回 null = 不覆盖继续原方法。

### 8.1 回填(2026-08-29,T15-T21 全通过)

测试目标:`net.minecraft.world.entity.monster.Zombie.aiStep()V`(自然生成 + `/summon` 双路触发);`test_pin_t15.groovy`(declare + override)与 `test_pin_t18.groovy`(仅 on)两脚本。

| # | 结果 | 实测锚点 |
|---|---|---|
| T15 | ✓ | `Pin pack 'zombie-aistep' declared ... next launch`;二次运行幂等 skip;`/gvr pins` 显示 `not applied this launch` |
| T16 | ✓ | 启动日志 `Groovier pin packs loaded: 2 pack(s), 1 target(s)`;Zombie 装载期 `applied (1 method(s))`(report.json pins 块) |
| T17 | ✓ | override 返回 true 后:enter hit 逐 tick 刷屏、僵尸完全冻结(含移动,travel 在 aiStep 链内)、**exit 不再触发**;自然生成实体同样命中(Drowned 经 super 调用继承生效) |
| T18 | ✓ | `/gvr disable test_pin_t15.groovy` 后 exit hit 逐 tick 触发(result=null),原方法行为恢复 |
| T19 | ✓ | 同 T18:disable 注销 enter 回调,查空放行,无异常 |
| T20 | ✓ | bogus 方法名:`matched no method 'groovierNoSuchMethod'` WARN + report 标 `error (no matching method)`,Zombie 正常加载 |
| T21 | ✓ | `/gvr pins remove` + `/gvr surgery remove` 均正常;**改进**:name 参数已加 Tab 补全(列出已安装包名) |

**开发期缺陷实录(三轮启动定位,勿重蹈)**:

1. **ANEWARRAY 误用 IntInsnNode**:ANEWARRAY 是类型指令(操作数 = 组件类型符号,走 `visitTypeInsn`),`visitIntInsn` 无符号 → modlauncher 串接 COMPUTE_FRAMES 帧模拟 NPE → `EntityType.<clinit>` 装链崩溃;
2. **ANEWARRAY 缺长度压栈**:改为 TypeInsnNode 后未压 int 长度 → 栈下溢读垃圾 → 执行期 `NegativeArraySizeException: -1`(帧模拟读局部变量槽当值,模拟"成功"但语义错误);
3. **void 跳过分支缺 RETURN**:覆盖分支只 `POP` 未 `RETURN`,掉进汇合点继续执行原方法——栈纪律合法(Analyzer/JVM 验证全过),纯语义缺陷,表现为 enter hit 打印但 override 永不生效。教训:**数据流自检只能兜栈纪律,兜不住语义直落**;wrapper 生成后已加 `Analyzer(BasicVerifier)` 自检(1/2 类缺陷现会在 transform 期 fail-safe)。

**工程教训**:coremod 侧日志(`Groovier/Pin*` logger)不在 `com.bluesky.groovier` 包下,只进 latest.log 不进 groovier.log,观测锚点在 latest.log;PowerShell `Set-Content` 默认写 UTF-16/BOM,写 pin.txt/surgery.txt 需 UTF-8 无 BOM。

> 2026-08-29 后续:logger 名已统一归入 `com.bluesky.groovier.coremod.*` / `com.bluesky.groovier.hooks.*`,上述观测锚点现**同时进 groovier.log**。

## 9. refer 反编译导出用例(6.5,2026-08-29 交付)

机制:coremod `ReferStore` 在 coprocessor 回调(mixin 应用后、define 前)把 `groovier-refer.txt` 命中类的残局字节落盘 `local/refer/classes/`;GAME 层 `ReferExporter`(Vineflower 1.12.0)后台反编译为 `groovy_scripts/refer/*.java`(服务器启动自动一次,`/gvr refer` 重跑);`/gvr classtree` 生成 `groovy_scripts/refer/classtree.txt`。

| # | 用例 | 操作 | 预期 |
|---|---|---|---|
| T22 | 捕获落盘 | refer.txt 写精确类 + 前缀,启动进存档 | 启动日志 `Groovier refer loaded: N rule(s) -> M target class(es)`;`local/refer/classes/<包路径>/<类>.class` 存在 |
| T23 | 自动导出 | T22 启动完成后看 groovier.log | `Refer export done: N file(s) -> groovy_scripts/refer/`;产物 .java 可读(内容含存活 mixin 行为 = 复刻基准) |
| T24 | 混淆跳过 | refer.txt 精确写一个外部类简单名 ≤2 的类,脚本 `Class.forName` 强制加载 | `Refer export: skipping likely obfuscated class ...` WARN;该类无 .java;其余类正常导出;classes/ 下字节仍保留 |
| T25 | classtree | OP 执行 `/gvr classtree` | 回执 `classtree written: N class(es)`;classtree.txt 层级正确:外部父类 `(extends X)` 注明、`#` 深度缩进、混淆标 `[O]` |
| T26 | 手动重跑 | 触发更多目标类加载后执行 `/gvr refer` | 回执 started;日志 `Refer export done`;产物更新,旧 .java 已清理(快照化) |
| T27 | classtree 过滤 | OP 执行 `/gvr classtree <FQCN片段>`(如 `Zombie`) | 回执 `classtree written: N class(es)`(N 为过滤命中数);classtree.txt 仅含命中子树,外部父类 `(extends X)` 注明 |
| T28 | 无配置回归 | 移走 refer.txt 并清空 local/refer + refer/ 产物,重启进世界 | 启动日志 `refer config not present (...), refer capture disabled` 且无 ReferTransformer 注册;`Refer export skipped: no captured classes`;`/gvr classtree` 回执 `No refer classes captured`;全程无异常 |

**验收记录(2026-08-30,1.21.1 NeoForge 21.1.235 dev)**:
- T22 ✅(`local/refer/classes/` 按包路径落盘);T23 ✅(启动自动导出 **58 file(s), 0 skipped**;Zombie.java 可读且含 `GroovierHooks` import——钉子包装字节反编译可见,反向佐证 6.3 注入);
- T24 ✅(候选 = `dev.latvian.mods.kubejs.util.ID`,简单名 `ID` 2 字符;探针脚本强制加载 → WARN skip + `58 file(s), 1 skipped`;ID.class 捕获、ID.java 未导出);
- T25 ✅(`/gvr classtree` 无参 → 59 class(es) 全量树);T26 ✅(`/gvr refer` 重跑 58 file(s));T27 ✅(`/gvr classtree Zombie` → 1 class(es),`net.minecraft.world.entity.monster.Zombie (extends net.minecraft.world.entity.monster.Monster)`);
- T28 ✅(移配置 + 清产物后:capture disabled / export skipped / classtree 回执三链齐活,ReferTransformer 零注册)。
- **6.5 全部用例(T22-T28)验收通过,补录(2026-08-30)**。
- **诊断插曲(勿重蹈)**:首轮验收 vineflower 报 `NoClassDefFoundError: IResultSaver` 且炸 server——根因 = build.gradle 缺 `additionalRuntimeClasspath`(MDG dev 层清单只收该配置,见 SPEC §6.5);修复 = 三件套补齐 + onServerStarting try/catch 防护。dev 环境排此类问题可用探针脚本对比各包加载器(`class.classLoader`),注意沙箱编译期黑名单(`ClassLoader`/`ProtectionDomain` 不可引用)。T24 此类强制加载探针:`Class.forName(name, true, GroovyLog.class.classLoader)` 可用(非黑名单)。

注意:
- refer 产物为 `.java`/`.txt`,脚本扫描只认 `.groovy`,refer/ 目录天然安全;
- 导出在后台守护线程执行,防重入(进行中再触发会 skip);
- Vineflower 经 jarJar 内嵌,生产 jar 无需额外依赖;反编译不带游戏 classpath(独立解析,复杂泛型签名还原可能保守,作参考足够)。

## 10. 整类覆盖用例(6.4,2026-08-30 交付)

机制:GAME 层 `OverrideManager` 在主模组构造期与 `/groovier reload` 扫描 `groovy_scripts/override/*.groovy`(纯类文件,基于 refer 模板复刻)影子编译(目标类解析取 refer 残局字节 / jar 原始字节,不触发目标类加载)→ `ApiDiff` 签名契约校验(public/protected 成员缺失 = 阻止)→ 通过者落盘 `local/override/classes/<fqn>.class`;coremod `OverrideStore` 在目标类 coprocessor 回调(mixin 后、define 前)整类替换。配置 `config/groovier-override.txt`(语法同 refer:精确类名 + `prefix.*`);清单 `/gvr override`。

| # | 用例 | 操作 | 预期 |
|---|---|---|---|
| T29 | 无配置回归 | 不建 override.txt,重启进世界 | 日志 `Groovier override config not present, override disabled`;rebind 输出 `no override sources, nothing bound`;无 OverrideTransformer 注册;启动与脚本引擎全程无异常 |
| T30 | 绑定 + 替换全链 | ① refer.txt 加 `com.bluesky.groovier.refer.ReferClasstree`,启动后 `/gvr classtree`(触发加载捕获)+ `/gvr refer` 导出模板;② 模板复制为 `groovy_scripts/override/ReferClasstree.groovy`,`dump()` 返回值追加标记(如 `" [OVERLIVE]"`),修掉 Groovy 编译不适的语法;③ override.txt 写同类名,重启 | 启动日志 `Override bind: ... registered`;`/gvr override` 显示 `registered` + `injected: applied`;`/gvr classtree` 输出含 OVERLIVE 标记 |
| T31 | 签名契约阻止 | 在 T30 源上删除一个 public 方法(如 `dump(String)`),重启 | bind 日志 `blocked by API diff`;`/gvr override` 显示 `blocked` + missing 成员清单;类按原身加载(`dump` 行为不变);落盘目录无该 .class |
| T32 | reload 重绑定 | 修好 T31 源,OP 执行 `/groovier reload` | bind.txt 刷新为 `registered`(后台重编译);已加载类保持原身(机制边界),懒加载类新替换即时生效 |
| T33 | 钉子冲突告警 | 对 T30 目标类同时 `Pins.declare` 并重启 | report.json overrides 块该类状态含 `pin pack(s) exist but will be discarded` 告警;钉子包装被整类替换丢弃 |

注意:
- 整类替换 = 放弃目标类上其他模组 mixin/AT 改动(6.4.1 明示取舍);若 refer 残局含存活 mixin,ApiDiff 会以缺失成员形式逼出复刻;
- refer 残局含钉子包装时,`groovier$` 前缀成员不计入契约(钉子包装可整体去留,`Pins.override` 回调表对复刻的包装仍生效);
- 基准缺失(refer 未捕获)时降级用游戏 jar 原始字节(bind 行标 `baseline=original-jar`;其他模组的 mixin 改动不在基准内,需人工复核);
- 私有成员缺失为告警级(bind 行 WARN):同 nest 内部类 nestmate 访问可能 IllegalAccessError,复刻时应保留;
- override/ 目录已被脚本扫描排除(ScriptManager 顶层 refer/、override/ 跳过),覆盖源不会作为脚本执行。

**验收记录(2026-08-30,T29-T33 全通过,1.21.1 NeoForge 21.1.235 dev)**:
- T29 无配置回归 ✅:`OverrideStore: override disabled` + `Override bind: no override sources, nothing bound`,report.json overrides 块空,客户端正常(首轮启动遇 FML 偶发注册表并行崩溃 `neoforge:swim_speed unbound`,栈中无 Groovier 帧,重启未复现);
- T30 全链 ✅:refer 模板复刻 `override/ReferClasstree.groovy`(加 OVERLIVE 标记)→ bind `registered (baseline=residual)` → 类加载 `override applied (10 method(s), 4 field(s))` → `/gvr classtree` 输出 `60 class(es) ... [OVERLIVE]`,`/gvr classtree Zombie` 过滤分支同样生效;
- T31 契约阻止 ✅:复刻源删 public `dump(String)`(注意:Groovy 禁止同名 private/public 重载混用,降 private 会 compile-error,须真删或并入其他方法)→ bind `blocked | missing 1 external member(s): method dump(Ljava/lang/String;)Ljava/lang/String;`,落盘无 .class,`/gvr classtree` 原身行为(无 OVERLIVE);
- T32 reload 重绑定 ✅:恢复源 → `/groovier reload` → bind 刷新 `registered (3 added)`;已加载类保持原身(再次 classtree 仍无 OVERLIVE = 机制边界确认);
- T33 钉子冲突 ✅:`Pins.declare` ReferClasstree.dump + 冷启动 → report.json overrides 块 `applied; WARNING 1 pin pack(s) exist but will be discarded by class override`,log 同步告警,钉子包装被整类替换丢弃且覆盖类功能正常(OVERLIVE 仍在)。测试钉子包与脚本已移 `waste/`;
- 验收过程中修复的三个实现缺陷(详见 SPEC §6.4 验收实证):FQN 归一化、CompileStatic 强制(含 closure 辅助类 NCDFE 风险)、沙箱守卫加载器作用域。
