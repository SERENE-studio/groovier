# Groovier SPEC(现状基线 + 愿景)

> 本文件记录 groovier 的**已实现现状**(基线)与**最新愿景**(两阶段覆盖模型 + 拦截能力矩阵),作为后续完善的目标。目标:NeoForge 1.21.1 / ModDevGradle 2.0.141 / Java 21 / Groovy 4.0.33(JiJ 内嵌)。

## 1. 定位

通用 Java 运行时修改工具(服务端为主,客户端最小脚本集见 §10.4):用 Groovy 脚本直接修改 Minecraft/模组的 Java 运行时,支持 `/groovier reload` 热重载与脚本动态启停,实现"非模组的兼容性支持"——所有 compat 通过脚本实现,无需为每个模组写 Java 适配层。

## 2. 已实现功能

### 2.1 脚本引擎
- 扫描服务器运行目录下 `groovy_scripts/`(递归,`.groovy`),按文件路径排序执行;目录缺失时自动创建
- 脚本文件(继承 `Script`)→ 创建实例注入绑定执行;纯类文件 → 调 `$getLookup` 触发静态初始化
- 全部脚本共享同一 `GroovierClassLoader`,脚本间可互相引用类
- 脚本类名由相对路径唯一化(修复:消除子目录同名文件的类缓存覆盖)
- 每个脚本独立 Binding(修复:顶层变量隔离,共享状态经 `globals`)

### 2.2 脚本绑定(脚本内直接可用)
| 绑定 | 说明 |
|---|---|
| `Log` | 日志(info/warn/error) |
| `EventManager` | NeoForge 事件监听,闭包注册;归属按脚本分组,回调内注册亦正确归属(修复) |
| `globals` | 全局变量:跨脚本共享、reload 保留;`set/get/remove/contains`;`asMap()` 返回不可变快照(修复) |
| `Events` | KubeJS 联动触发 `Events.fire(name, data)`(可选依赖,未加载时 no-op) |

自动 import:`Level/Block/Item/ItemStack/BlockPos/CompoundTag/ResourceLocation/ServerPlayer/Entity/Player/ServerTickEvent/PlayerEvent` 等。

### 2.3 事件监听
- `EventManager.listen { EventClass e -> ... }`,闭包唯一参数类型决定监听的事件
- 监听器按脚本分组登记(`GroovierEventManager`),支持全量注销(reload)与单脚本注销(禁用)
- 抽象事件不可监听(如 `ServerTickEvent` 须监听 `Pre/Post`)
- 事件回调内再注册监听器:通过闭包 owner 链反查脚本归属,不再挂到空分组(修复)

### 2.4 热重载与脚本动态启停
- `/groovier reload`:注销全部监听 → 丢弃旧类加载器 → 新建 → 重编译执行(globals 保留)
- 启用状态约定:globals 布尔键 `groovier.enabled.<相对路径>`(缺省 = 启用)
- `globals.set/remove` 变化时回调检测状态翻转:启用 → 立即执行脚本;禁用 → 注销其监听器;回调防重入(修复)
- 支持 `/groovier enable|disable <script>` 命令切换(写入 globals,动态生效;同步编译执行,大脚本会阻塞 tick,属已知取舍)

### 2.5 沙箱(宽松默认 + 高危拦截)
编译期(AST,`GroovierTransformer` CANONICALIZATION)+ 运行时(黑名单类 → 空 MetaClass)双通道拦截:
- 拦截:进程执行(`System.exit/gc/setSecurityManager`、`String.execute`、`Runtime/ProcessBuilder`)、类加载器操作(`ClassLoader/GroovyShell/GroovyClassLoader`)、网络(`java.net/javax.net/java.rmi`)、`groovy.grape`(@Grab 依赖拉取,同时禁用 Grab AST 变换)、`Scanner/Thread.stop` 等
- `@GroovyBlacklist` 注解:groovier 内部敏感 API 标记,脚本调用即报错
- 允许:反射(`java.lang.reflect`)、文件 IO(`java.io`)、任意 mod 类访问(定位是防脚本搞崩服务器,非防恶意代码)
- 方法/字段级拦截仅编译期生效;运行时反射可绕过方法级黑名单,为已知限制(死代码 `isValid(Method/Field)` 已清理并澄清注释)
- EMC(`ExpandoMetaClass`)保持可用:支撑"脚本修改运行时类"(Groovy 侧方法覆盖)愿景;黑名单类仍由 `GrSMetaClassCreationHandle` 优先拦截,EMC 无法覆盖黑名单类(修复)

### 2.6 命令(需 OP)
```
/groovier reload                 后台全量热重载(异步,完成后广播结果)
/groovier enable|disable <s>     启用/禁用脚本
/groovier list                   列出全局变量名(点击可复制)
/groovier scripts|script         列出脚本及启用状态(点击可复制)
/groovier val <name>             打印全局变量的值
/groovier next <structure|#tag>  在最近的"未生成区块"中定位结构(点击坐标传送)
/groovier register [type] [filter] dump 注册表到 local/register/(json+csv,静态+动态通用枚举;type 如 item;filter 按 namespace/路径子串过滤)
/groovier global                 dump 全部全局变量到 groovier.log
/groovier surgery [remove <n>]   手术包清单/删除(report.json surgeries 块)
/groovier pins [remove <n>]      钉子包清单/删除(manifest + 注入状态 + 回调数)
/groovier classtree              refer 继承树 → groovy_scripts/refer/classtree.txt(# 缩进,混淆标 [O])
/groovier refer                  后台反编译导出 refer 类(local/refer/classes → groovy_scripts/refer/)
/groovier override              整类覆盖绑定清单(绑定 + 注入状态,产物 local/override/)
/groovier help                   中英双语用法(按客户端语言切换)
/gvr <子命令>                    以上全部子命令的等价短前缀
```

### 2.7 日志
- 独立日志文件 `logs/groovier.log`(log4j2 动态 FileAppender,`com.bluesky.groovier` 包),类似 KubeJS 的 `logs/kubejs`;控制台与 `latest.log` 同样可见

### 2.8 方法钉子(6.3 原身通路,2026-08-29 交付并实机验收,T15-T21)
- 两段式:脚本 `Pins.declare(...)` 写 `local/pins/<name>/pin.txt` → 冷启动类加载期(mixin 前)核心侧重命名包装注入查询点 → 脚本 `Pins.override(key){thiz,args->...}` / `Pins.on(key){thiz,args,result->...}` 运行期生效
- 机制与边界详见 §6.3;命令 `/groovier pins [remove <name>]`;测试用例 T15-T21 见 `docs/测试指导-mixin手术.md` §8

### 2.9 KubeJS 联动(可选依赖,骨架完成)
- `compileOnly` 依赖 KubeJS/Rhino(本地 `libs/` jar),运行时 `ModList.isLoaded("kubejs")` 检测
- jar 内含 `kubejs.plugins.txt`,KubeJS 加载时自动注册插件 `GroovierKubeJSPlugin`
- 事件组 `GroovierEvents.fire("事件名", event => ...)`,按事件名(String extraId)分发,负载 `event.name` / `event.data`(Map)
- 触发链:groovier 脚本 `Events.fire(name, data)` → `GroovierKubeJS`(主包门面,检测 + 懒加载路由)→ `GroovierKubeJSApi` → `FIRE.post(ScriptType.SERVER, ...)`
- 无 KubeJS 环境安全 no-op(已回归验证,无 NoClassDefFoundError)
- **API 签名静态验证通过**(javap 对照 `libs/kubejs-neoforge-2101.7.2-build.368.jar`):`EventGroup.server` → `EventHandler.supportsTarget(EventTargetType.STRING)` → `TargetedEventHandler<String>`;`post(ScriptTypeHolder, String, KubeEvent)` 参数齐备(`ScriptType.SERVER implements ScriptTypeHolder`);`BindingRegistry.add(String, Object)` 匹配。仅剩真机运行实测(整合包含 KubeJS 环境)。

## 3. 架构(包结构)

```
com.bluesky.groovier
├── Groovier.java                  @Mod 入口;ServerStarting / RegisterCommandsEvent 挂点
├── GroovierKubeJS.java            KubeJS 联动门面(运行时检测 + 懒加载路由,不引用 KubeJS 类)
├── api/                           Log / GlobalManager / EventsBridge / GroovierLogFile / @GroovyBlacklist
├── engine/                        GroovierClassLoader(parent = groovier mod 类加载器,关闭重编译)
│                                  ScriptEngine(编译执行单脚本,唯一类名,设置事件归属脚本上下文)
├── event/                         GroovierEventManager(闭包→监听器,按脚本分组,owner 链归属解析)
├── sandbox/                       GroovierSandbox(绑定/import/编译配置/reload 编排,EMC 可用)
│   ├── ScriptManager              脚本注册表 + globals 启停检测(防重入)
│   ├── security/                  GroovySecurityManager(黑名单判定)/ GrSMetaClassCreationHandle /
│   │                              BlackListedMetaClass(空 MetaClass) / SandboxSecurityException
│   └── transformer/               GroovierCompiler(CompilationCustomizer) / GroovierTransformer(AST 拦截)
├── command/                       GroovierCommand(/groovier 子命令)
└── kubejs/                        GroovierKubeJSPlugin / GroovierKubeJSApi / GroovierKubeEvent(仅 KubeJS 存在时加载)
```

## 4. 关键机制

- **类加载链**:`GroovierClassLoader` 父加载器 = groovier 自身 mod 类加载器(NeoForge 中 mod 类平级互见、游戏类在父链),脚本类可访问游戏类 + 任意 mod 类;`setShouldRecompile(false)`;reload 整体丢弃新建,防泄漏
- **脚本类唯一命名**:脚本类名由相对路径编码生成(如 `sub/foo.groovy` → `Groovier_sub_foo`),消除 `GroovyClassLoader.parseClass(File)` 按文件名推导类名导致的同名覆盖,并为监听器归属反查提供依据
- **监听器生命周期**:执行脚本时 `EventManager.setCurrentScript` 标记归属;回调内注册经闭包 owner 链(嵌套闭包 → 脚本实例)反查归属;reload/禁用用同一实例 unregister
- **绑定隔离**:每脚本独立 `Binding`(模板浅拷贝),顶层变量互不可见;跨脚本共享统一走 `globals`
- **globals 键约定**:`groovier.enabled.<脚本相对路径>` 为启停开关;GlobalManager.onChange 驱动动态生效,防重入
- **EMC 可用**:不禁止自定义 MetaClass 查找,脚本可用 `ExpandoMetaClass` 对 Groovy 侧调用做方法覆盖(愿景支撑);黑名单类由 `GrSMetaClassCreationHandle` 在 `createNormalMetaClass` 优先拦截
- **懒加载隔离**:一切引用 KubeJS 类的代码都在 `kubejs/` 子包,仅当检测到 KubeJS 才加载,保证无 KubeJS 环境安全

## 5. 已验证(冒烟)

- S1:脚本引用 MC 类 + Log 输出(`hello.groovy`)
- S2:ServerTickEvent.Post 监听持续触发(`event.groovy`)
- S3:globals 驱动脚本动态启停,监听器无泄漏(`toggle.groovy`)
- S4:高危操作编译报错,服务器不崩(`test_sandbox.groovy`)
- 回归:无 KubeJS 环境 `Events.fire` 安全 no-op(`test_events.groovy`)
- KubeJS 联动:API 签名静态验证(javap)通过;真机运行待整合包实测

## 6. 魔改方案体系(推倒重写 2026-08)

### 6.1 总纲:四级方案,按侵入度排序

兼容性手术按侵入度从低到高,能用低级方案绝不用高级:

| 优先级 | 方案 | 时机 | 侵入度 | 状态 |
|---|---|---|---|---|
| ① | 事件(NeoForge API) | 运行时 | 零 | ✅ 已实现 |
| ② | 加载时方法钉子 | 类首次加载(mixin 之后) | 方法级 | ✅ 已实现(原身通路;残局通路预留) |
| ③ | 加载前期整类覆盖 | 类首次加载 | 类级 | ✅ 已由 ④ 通道实现(2026-08-30,残局 coprocessor 窗口,见 §6.4) |
| ④ | 反编译导出 + 覆盖闭环(手术化) | 类首次加载 + 运行时后台 | 类级 | ◐ 导出工具链 ✅(2026-08-29)/ 覆盖绑定 ✅(2026-08-30,§6.4 落地状态) |

**已废弃:调用点重定向**——NeoForge 已把其认为有价值的方法在源头事件化(源码补丁直接写 `EventHooks.onXxx`),通用 redirect 无必要;且调用点重定向需枚举调用方、反射/invokedynamic 漏网,代价高于收益。

关键事实(调研):NeoForge 1.21.1 存在 `ICoreMod`/`ITransformer` 加载期转换通道(`transform()` 懒执行于目标类首次加载,可返回替换字节码);但 `targets()` 静态声明,脚本无法全动态注册目标。

### 6.2 第一优先:事件与运行时操作(零侵入)

- 原则:行为已有对应 NeoForge 事件的,一律用 `EventManager` 监听,与主流一级模组同构。NeoForge 补丁已把其认为有价值的行为事件化(`EventHooks.onXxx` 源头打点);**模组自行添加、未事件化的方法**走 6.3。
- 两阶段执行时机(针对需要 `MinecraftServer`/`Level` 实例的运行时操作):**注册**(`ServerStartingEvent`)越早越好(覆盖完整生命周期);**应用**(`ServerStartedEvent` + 数据包重载后)越晚越好(实例就绪、避免被数据包 reload 冲掉);应用阶段幂等、随数据包 reload 重复应用。`/groovier reload` 仅重跑注册阶段。
- 拦截能力矩阵(现状):

| 目标 | 机制 | 阶段 | 状态 |
|---|---|---|---|
| 物品:右键使用 | `PlayerInteractEvent.RightClickItem` setCanceled | 注册 | 可用(事件) |
| 物品:食用/饮用 | `LivingEntityUseItemEvent.Start/Finish` | 注册 | 可用(事件) |
| 物品:拾取 | `ItemEntityPickupEvent.Pre` | 注册 | 可用(事件) |
| 物品:合成 | 配方移除(见配方行) | 应用 | P1 |
| 物品:创造页签隐藏 | `CreativeModeTabs` 内容操作 | 应用 | P1 |
| 方块:放置 | `BlockEvent.EntityPlaceEvent` setCanceled | 注册 | 可用(事件) |
| 方块:破坏 | `BlockEvent.BreakEvent` setCanceled | 注册 | 可用(事件) |
| 方块:交互 | `PlayerInteractEvent.RightClickBlock` setCanceled | 注册 | 可用(事件) |
| 方块:掉落 | `BlockDropsEvent` 过滤 | 注册 | 可用(事件) |
| 配方:移除 | `RecipeManager.byType/byKey` 操作 | 应用 + reload 重挂 | P1 |
| 结构:禁用 | `ServerStructureManager`/`StructureTemplateManager` 缓存操作 | 应用 | P1 |
| 实体:生成 | `MobSpawnEvent.PositionCheck/FinalizeSpawn` | 注册 | 可用(事件) |
| 实体:属性 | `EntityAttribute` 注册值修改 | 应用 | P1 |
| 任意方法 | 加载时方法钉子(6.3) | 类加载期 + 注册 | P1 |

设计取舍:**"禁用物品/方块/实体"以拦截功能(事件取消)为主,不依赖注册表条目移除**(注册表 freeze 后条目不可删);配方/结构等数据包内容在应用阶段直接操作运行时对象。

### 6.3 第二优先:加载时方法钉子(默认主方案,时机敏感)

**方法冲突占 mod 冲突的绝大多数**(事件/方法体层面即可解决),钉子为默认主方案;整类覆盖(6.4/6.5)仅用于结构级需求(字段/构造器/新增成员/整体复刻)。

目标:**模组自行添加、无事件的方法**(NeoForge 不碰模组方法)。形态:运行时覆盖表(`override(key){...}` 重写 / `on(key){...}` 暴露为伪事件),reload/禁用动态增删;与 EMC 的区别:查询点对 Java 直接调用同样生效。

**注入机制(2026-08-29 实现定稿,原身通路)**:**重命名包装**(rename + delegate wrapper),替代原设想的"方法体首行原地注入"。理由:原地插入必须 COMPUTE_FRAMES,ASM `getCommonSuperClass` 在 SERVICE 层无法解析 mod/MC 类型(类未加载)→ 错误栈帧 = VerifyError 风险;包装法只生成 JDK 类型代码,栈帧手写零重算(唯一 F_FULL 帧在分支汇合点),原方法体字节零改动(mixin 兼容面最小),语义等价(查询点在方法入口,未命中透明放行)。

- **核心侧**(coremod 包):`PinStore` 读 `local/pins/<name>/pin.txt`(target/method/descriptor?/enabled);`PinningTransformer`(ITransformer,mixin 前)对命中方法改名 `groovier$orig$<name>` + 同名包装调 `GroovierHooks.enter/exit`;fail-safe(异常丢弃改写副本返回原字节);状态落 report.json `pins` 块
- **主模组侧**:`hooks/GroovierHooks`(零依赖查询点宿主,被生成字节码按名引用;enter 首个非 null 回调 = 覆盖返回值,exit 仅原方法实际执行后触发 = on 伪事件);`api/PinsApi`(绑定 `Pins`:declare 两段式冷启动生效 / override / on / list / remove);生命周期接入 ScriptManager(reload reset、禁用/失败回滚按脚本注销)
- **两段式**(与 M3 手术包同构):脚本运行期 `Pins.declare` 写清单 → 下次冷启动类加载期注入 → 脚本 `Pins.override/on` 注册运行期回调立即生效
- **边界(定案)**:`<init>/<clinit>`/native/abstract/synthetic 不钉;重载不写 descriptor 时全部钉住共用 key(类.方法名);wrapper 不复制 throws 清单(verifier 不强制);void 方法覆盖约定:闭包返回任意非 null 即直接返回;继承树自动遍历未做(子类重写需显式声明 target,自动遍历待 6.5 扫描基建)
- **B 原身通路已实现**;**残局通路(预留)**:`MixinCoprocessor.postProcess` 回调中注入(残局 ClassNode,define 前)——钉子基于完整结果,兼容性最优,依赖 coprocessor 基础设施(已就绪),实施时评估。

### 6.4 第三优先:加载前期整类覆盖(侵入最大)

按**包/类路径**匹配,把目标类整类替换为脚本编译类,可传递至整个继承链(覆盖基类 → 子类继承覆盖版)。

**继承链选项**:
- 不传递(默认):仅替换目标类自身;
- 传递:把整条继承链的相关类都覆盖重写。

三个衍生问题:

**6.4.1 时机(最终残局)**:作为兼容性服务模组,手术最佳时机是所有类都 mixin 完之后,留下最终残局由 groovier 覆盖。但**整类替换语义上会丢弃其他模组对该类的字节码改动**(mixin/AT)——这不是时机能解决的,必须明示取舍:目标类若被其他模组 mixin 过,整类覆盖 = 放弃其改动,脚本覆盖类需自行复刻;否则应改用方法钉子(6.3)。

**6.4.2 methodNotFound 处理**:覆盖类破坏原类公开 API 时,调用方抛 `NoSuchMethodError/NoSuchMethodException`。三道防线:
1. **签名契约**:覆盖类必须保留原类全部被外部引用的方法签名(工具扫描原类字节码生成契约清单);
2. **桥接**:确需改签名时,保留旧签名方法并转发到新实现;
3. **比对工具**:覆盖前对原类/覆盖类做 API diff,报告破坏性变更并阻止应用。

**6.4.3 夺舍(静态参与编译)**:整类覆盖天然支持为类**新增方法与属性**——脚本覆盖类在编译期即静态参与原类结构。兼容性风险:
- 新增成员可能与附属模组的 mixin/反射/字段访问冲突;
- 脚本类基于原版结构编译,若目标类被 mixin 改动过结构(新增字段/私有方法),须以**最终字节码**为基准校验(字段表/签名比对)。

**落地状态(2026-08-30,整类覆盖通道已交付,实机验收 T29-T33 全通过)**:
- 配置:`config/groovier-override.txt`(行式 txt,语法同 refer:精确类名 + `prefix.*`);目标是"允许整类替换的白名单"——覆盖源编译产物的 FQN 必须命中配置才注册(防同名类劫持);
- GAME 层(`com.bluesky.groovier.override`):`OverrideManager`(主模组构造期 + `/groovier reload` 后台重跑)扫描 `groovy_scripts/override/*.groovy`(纯类文件,基于 refer 模板复刻)→ **影子编译**(parent 影子加载器:目标类解析优先取 refer 残局字节、其次父加载器 resource 原始字节,就地 define——编译期绝不触发目标类真实加载,否则 coprocessor 回调在绑定落盘前发生,替换永久错过)→ `ApiDiff`(6.4.2 防线 3:契约 = 非 synthetic/bridge 的 public/protected 方法含构造器与字段;`groovier$` 前缀/`<clinit>` 不计;public/protected 缺失 = 阻止,private 缺失 = WARN[nestmate 风险])→ 通过者落盘 `local/override/classes/<fqn>.class`(快照化)→ 绑定报告 `local/override/bind.txt`;基准缺 refer 残局时降级 jar 原始字节(bind 行标 `baseline=original-jar`);
- coremod 侧:`OverrideStore`(`groovier-override.txt` 解析 + coprocessor 回调整类替换:override 字节 parse 成 ClassNode,结构内容整体换入残局 node,类名强制保留;自引用超类修正——覆盖类 extends 目标自身时改指原父类;nestMembers 保留原类清单保 nestmate 访问;fail-safe 备份引用异常恢复原 node)+ `OverrideTransformer`(独立 coprocessor 注入触发器;前缀展开无果时回退锚定 MinecraftServer);`CoprocessorInjector.dispatchPost` 三消费:PostWatch → OverrideStore → ReferStore(顺序保证 refer 取证的是替换前残局字节);
- report.json 新增 `overrides` 块(applied / no override bytecode / error);`/gvr override` 清单 = bind 报告 × 注入状态;
- **与钉子的互斥**:PinningTransformer(pre-mixin)注入的包装会被 coprocessor 整类替换丢弃——OverrideStore 检测同类存在钉子时告警;refer 产物含钉子包装时,复刻类保留包装 + 原方法则 `Pins.override` 回调表仍生效;
- 已知边界:替换后类 = 编译期快照,已加载类无法再替换(reload 仅刷新后续懒加载类);脚本扫描已排除顶层 `override/`/`refer/` 目录;dev 环境前缀规则无法从 mods 目录展开(GAME 侧用 startsWith 匹配不受影响,仅 coremod 触发器展开受限,有锚定兜底);
- **验收实证(2026-08-30,T29-T33,勿回退)**:
  - **覆盖类必须强制 `@CompileStatic`**(OverrideManager 编译配置注入 `ASTTransformationCustomizer(CompileStatic)`),三个实机级理由:① 动态 Groovy 的 lambda 编译为 `$_closure` 辅助类,覆盖类由游戏加载器定义而辅助类不在游戏 classpath → 执行到闭包路径 `NoClassDefFoundError`;② 覆盖类的**动态分派**会触发全局沙箱守卫(见下),静态编译直调零 MOP 零 meta class 查询,守卫永不触发;③ CompileStatic 下 lambda 走 invokedynamic,无辅助类。复刻源须适配静态编译(如 `Files.walk(...).toList()` 替代 for-each Stream;禁止跨行 `+` 开头表达式拼接);
  - **沙箱运行期守卫作用域修正**(`GrSMetaClassCreationHandle.isSandboxWorld`):全局 MetaClassRegistry 句柄按类加载器划界——JDK 类(null 加载器)始终守卫(Runtime/ProcessBuilder 逃逸面)、GroovierClassLoader 体系内类守卫、游戏加载器定义的可信类(含覆盖类)放行。否则覆盖类对 `sun.nio.fs.WindowsPath` 的合法动态分派被黑名单误伤(实证);
  - **FQN 归一化**:ClassReader 解出的 `ClassNode.name` 为内部名(斜杠),OverrideStore 比对前必须 `replace('/', '.')`(T30 首轮 fail-safe 拒换实证);
  - report.json overrides 块 applied 状态附加钉子冲突告警(`applied; WARNING n pin pack(s) exist but will be discarded by class override`),与测试指导 §10 T33 验收对齐。

### 6.5 第四种方案:反编译导出与覆盖闭环(最具有手术价值)

整类覆盖(6.4)的**手术化落地**:以"mixin 后最终字节码"的反编译产物为参考模板,脚本覆盖类据此复刻,实现"**读取最终产物 → 替换为具有相同功能的 groovy**"——mixin 行为以复刻形式保留,打通 6.4.1 整类覆盖"丢 mixin 改动"的取舍。

**组成(四件套)**:

1. **配置**(`config/groovier.yaml` 或 jar 内默认):
   - `targets`:包前缀通配(`com.example.*`)+ 精确类名混用;启动早期扫描 mod jar/classpath 枚举匹配类,生成 `ITransformer.targets()`
   - 混淆标注:启发式检测或 yaml 显式标注混淆模组
2. **反编译导出**(运行时后台):残局捕获 = `MixinCoprocessor.postProcess` 回调(mixin 应用后、define 前,见 §10.1 精准注入)→ 后台线程反编译(内嵌 Vineflower,NeoForge 开发环境同款)→ 导出 `groovy_scripts/refer/<包路径>/<类名>.java`;混淆类**跳过 + 警告**
3. **classtree**(`/gvr classtree`):从 refer 已导出类的继承关系(`ClassNode.superName`)生成 `refer/classtree.txt`,`#` 层级缩进,混淆类标 `[O]`
4. **覆盖**(6.4 窗口):脚本作者基于 refer 的 `.java` 改写为同名 `.groovy` 覆盖类(可扫描目录,如 `groovy_scripts/override/`);yaml 目标命中 → 类首次加载时整类替换;签名契约/桥接/比对工具防 `methodNotFound`(6.4.2)

**关键约束**:
- 最终字节码的捕获通路(2026-08-29 修正):pre 阶段 = ITransformer 回调;**残局(mixin 后)= `MixinCoprocessor.postProcess` 同步回调**(mod 侧无 post-mixin ITransformer/launch plugin 通道)——运行时 `ClassLoader.getResourceAsStream` 拿到的是 jar 原始字节码(不含 mixin),回调本身就是"类已加载"的触发点,无需轮询
- `refer/` 目录**列入脚本扫描排除**(递归扫描 `groovy_scripts` 会误执行参考脚本;产物为 `.java` 时天然安全)

**冲突诊断(双捕获;2026-08-29 机制修正)**:原设想"pre/post 两个 ITransformer,post 未调 = mixin 冲突"不成立(post-mixin ITransformer 不存在,见 §10.1)。修正后:
- **原身**:pre-mixin ITransformer 捕获(可靠);
- **残局**:`MixinCoprocessor.postProcess` 同步回调(§10.1 精准注入,观测类经 `groovier-postwatch.txt` 声明)→ pre/post 成对落盘 `local/mixin_invalidated/watch/`;
- **冲突信号**:report.json 状态字段(`invalidated`/`channel_failed`)+ 摘除明细(removed/kept);摘除失败 = 通道问题,非 mixin 冲突;mixin 应用内冲突由 mixin 自身 audit/日志暴露,groovier 汇总呈现(后续);
- 解析 mixin 配置(`mixin.json`)收集指向目标类的 mixin 字节码反编译导出("试图 mixin 的字节码")仍为 6.5 后续组成。

**时序**:
```
启动早期:扫描 yaml targets → 枚举类名 → 注册 ITransformer
类首次加载:transform 回调(全部 mixin 后)捕获最终字节码
  ├─ 后台线程:反编译 → refer/*.java(混淆类跳过+警告)
  └─ 若同名脚本覆盖类存在 → 整类替换(签名契约/比对工具校验)
运行时:/gvr classtree → refer/classtree.txt(继承树)
脚本作者:改 refer/*.java → 写 override/*.groovy → 下次启动生效
```

**已定案决策(grill 2026-08)**:混淆 = 跳过+警告;yaml = 包前缀+精确类名混用。

**落地状态(2026-08-29,导出工具链已交付)**:
- 配置:`config/groovier-refer.txt`(**行式 txt,与 postwatch 同构**;yaml 延后,零依赖解析优先)——精确类名 + `prefix.*`(扫 mods jar 展开),语法同 §10.1 黑名单;
- coremod 侧:`ReferStore`(coprocessor 残局捕获落盘 `local/refer/classes/<包路径>/<类>.class`,只取证不改写)+ `ReferTransformer`(refer 目标独立的 coprocessor 注入触发器);回调经 `CoprocessorInjector.dispatchPost` 分发(PostWatch + ReferStore);
- 主模组侧(`com.bluesky.groovier.refer`):`ReferExporter`——Vineflower **1.12.0**(jarJar 内嵌,`Decompiler.builder().inputs(File).output(DirectoryResultSaver)` 目录模式)后台反编译 → `groovy_scripts/refer/*.java`;混淆启发式 = **外部类简单名长度 ≤ 2 跳过 + 告警**(可读名混淆类检测不出,已知限制);staging/out 中间目录在 `local/refer/` 下,产物快照化(导出前清旧 `.java`);
- `ReferClasstree`(`/gvr classtree [filter]`):ClassReader 头部解析(`getClassName/getSuperName`,零全量 accept)生成 `groovy_scripts/refer/classtree.txt`,外部父类 `(extends X)` 注明,`#` 深度缩进,混淆标 `[O]`,每类恰好出现一次;可选 filter 为 FQCN 不区分大小写包含匹配(如 `classtree Zombie` → 单类子树);
- 触发:`ServerStartingEvent` 后台守护线程自动一次(防重入);`/gvr refer` 随时重跑(幂等;懒加载类此时已就绪);onServerStarting 对 ReferExporter 调用包 try/catch(Throwable)——dev 环境 vineflower 类路径缺失时降级为日志错误,不炸 server;
- **dev 类路径注意(2026-08-30 实证)**:MDG 的 dev 运行层清单(clientLegacyClasspath.txt,FML 据此建模块层)**只收 `additionalRuntimeClasspath` 配置**——vineflower 必须三件套齐:`implementation` + `additionalRuntimeClasspath` + `jarJar`,缺失则 TRANSFORMER 层加载 `IResultSaver` 抛 NCDFE(groovy 同理,勿删其 additionalRuntimeClasspath);
- 覆盖绑定(6.4 窗口)已于 2026-08-30 交付(见 §6.4 落地状态:`groovier-override.txt` + OverrideManager 影子编译 + ApiDiff 签名契约 + OverrideStore coprocessor 整类替换)。

### 6.6 时机总览

(2026-08-29 修正:priority 排序不存在;mod 侧可用时机 = ITransformer 阶段(mixin 前)与 MixinCoprocessor 回调(mixin 后、define 前),见 §10.1)

| 方案 | 时机 | 作用于 | 与其他模组的关系 |
|---|---|---|---|
| 事件 | 运行时(任意) | 运行时行为 | 无冲突 |
| 方法钉子 | 类首次加载:原身通路(ITransformer,mixin 前)✅ 已实现;残局通路(coprocessor,mixin 后)预留 | 方法体 | 原身通路可配合手术级摘除先净化冲突 mixin;残局通路基于完整结果(预留) |
| 整类覆盖 | 类首次加载(ITransformer 阶段) | 类定义 | 丢弃目标类上的其他字节码改动(需明示);可配合摘除保留存活 mixin |
| 反编译导出 + 覆盖闭环 | 类首次加载(coprocessor,✅ refer 捕获已实现)+ 运行时后台(✅ 导出/classtree 已实现) | 类定义 + 参考产物 | 参考产物基于最终残局(含存活 mixin) |
| mixin 摘除(§10.1) | 类首次加载,mixin 应用前 | mixin 注册表 | 类级全摘或手术级单项摘除,fail-safe |

### 6.7 实施顺序

- 现状:6.2 已就绪(事件 + 运行时操作);**§10.1 mixin 作废 + 手术级粒度 + coprocessor 残局通道已实现**(2026-08-29,含 `META-INF/services` Java ITransformationService 通道);**6.3 方法钉子原身通路已实现并实机验收**(2026-08-29,重命名包装 + GroovierHooks 覆盖表 + Pins 两段式,T15-T21);**6.5 导出工具链已实现**(2026-08-29,ReferStore 残局捕获 + ReferExporter/Vineflower refer 产物 + /gvr classtree)。
- ~~第一步:6.3 方法钉子~~ ✅(残局通路视兼容性需求评估);
- ~~第三步:6.5 导出工具链~~ ✅(refer 产物 + classtree);
- ~~第二步:6.4 整类覆盖(6.4.2 API 比对工具 + 覆盖绑定)~~ ✅(2026-08-30,§6.4 落地状态;实机验收 T29-T33 全通过,测试指导 §10);
- 后续:6.3 残局通路(coprocessor 注钉)与继承树自动遍历(基于 classtree 基建);
- 并行(M3):§10.1 脚本编排两段式(手术包 + 启动脚本早期化)。

## 7. 已知限制与预留

- 服务端为主;客户端最小脚本集已立项(双端允许 `.groovy` 不一致,见 §10.4)
- 方法钉子原身通路已实现(6.3,2026-08-29);已知边界:`<init>`/native/abstract/synthetic 不钉,继承树自动遍历未做(子类重写需显式声明),wrapper 不复制 throws,重命名使 `getDeclaredMethods` 多出 `groovier$orig$*` 条目(按名反射取方法不受影响,同名签名解析到包装器);残局通路(coprocessor 注钉)预留
- 配方/数据包操作 API、命令注册 API、注册表增删未实现(P1,现状为脚本直接操作运行时对象)
- 反射可绕过 Groovy 层沙箱的方法级黑名单(宽松定位的固有代价;`isValid(Method/Field)` 死代码已清理)
- **字节码级修改已加载类不可行**(无 `Instrumentation` 句柄);**未加载类经自有 `ITransformationService`(SERVICE 层)加载期转换已实现**——mixin 摘除 + 手术级粒度 + coprocessor 残局通道(§10.1,2026-08-29);方法级覆盖走加载时方法钉子(见 6.3)
- **类级别重写(脚本替代 .class)机制可行但 target 静态**:`ITransformer.targets()` 于加载早期定死,脚本无法全动态注册目标;启动早期已加载的核心类需 EarlyScript;整类覆盖丢弃目标类上的其他字节码改动(见 6.4)
- 脚本将对象塞入全局静态容器时,reload 后旧类加载器仍被强引用(脚本自身行为导致,无法根治)
- KubeJS 真机联动(实际整合包含 KubeJS 环境)未运行实测,签名静态验证已通过
- `/groovier enable|disable` 在 server thread 同步编译,大脚本会短暂阻塞 tick(正确性优先的取舍)

## 8. 缺陷修复记录

| # | 缺陷 | 修复 |
|---|---|---|
| B1 | `GroovierSandbox.classLoader` 字段死代码(reload 旧 loader 靠 GC) | 删除死字段(2026-08-14) |
| B2/B9 | 事件回调内注册监听器挂空 key,disable 注销不到 | owner 链归属解析(2026-08-14) |
| B3 | 子目录同名脚本类缓存覆盖 | 相对路径唯一类名(2026-08-14) |
| B4 | `isValid(Method/Field)` 死代码误导 | 清理 + 注释澄清(2026-08-14) |
| B5 | 共享 Binding 顶层变量污染 | 每脚本独立 Binding(2026-08-14) |
| B6 | `setDisableCustomMetaClassLookup(true)` 阻断 EMC | 移除,EMC 可用(2026-08-14) |
| B8 | `asMap()` 暴露可变 Map / `onGlobalChanged` 无防重入 | 不可变快照 + 防重入(2026-08-14) |

## 9. 关键文件索引

- 愿景:`d:\bluesky_fantaste\文档\groovier.md`
- MVP 设计:`d:\bluesky_fantaste\文档\groovier-mvp-design.md`(里程碑 M0-M5 已全部完成)
- 使用指南:`groovier\docs\模组使用指南.md`
- mixin 手术测试指导:`groovier\docs\测试指导-mixin手术.md`(随 §10.1 机制维护)

## 10. 需求迭代定案(grill 2026-08-28)

四项新需求经 grill 定案;端定位由"仅服务端"升级为"服务端为主 + 客户端最小脚本集"(§1 已同步)。

### 10.1 mixin 类级作废(原需求 1;2026-08-29 机制重构已实现)

> 原定案("post-mixin 回写原身字节码")经 modlauncher 11 源码调研**证伪**,机制重构如下。原否决"mixin 级粒度"的理由(diff 注入痕迹,脆弱)随机制变化**不再成立**——摘除在 mixin 注册层完成,无需 diff,故手术级粒度一并实现。

**源码实证结论(勿回退)**:
- mixin 在 modlauncher 11 中以 **Launch Plugin(AFTER 阶段)** 生效,不是 ITransformer;fml 侧 ITransformer 全部先于 mixin,`TransformList` 无 priority 排序 → "priority 晚于 mixin 的 ITransformer"/"post-mixin 回写时刻"**不存在**;
- mod jar 最远被拉入 SERVICE 层(`ModDirTransformerDiscoverer` 经 `META-INF/services` 声明 `ITransformationService`),launch plugin 只从 BOOT 层加载 → mod 侧无 post-mixin transform 回调;
- FML 以 newOpenModule/newAutomaticModule 构建层内 jar → 深层反射可行。

**实现机制(已交付)**:
1. **通道**:自有 Java `ITransformationService`(jar 内 `META-INF/services`,不写 coremods.json/js);`allExcluded()` 无调用方,**mod 双身份保留**;
2. **类级作废**:pre-mixin ITransformer 阶段缓存"原身"字节码 + 反射摘除 `MixinConfig.mixinMapping` 中指向黑名单类的条目 → 同一次类加载 mixin 查空,类以原身 define。**无"应用后覆盖"中间态**,失败 fail-safe(mixin 照常,报告标 `channel_failed`);
3. **手术级作废**(粒度反转,见上):黑名单语法 `target::mixin`(双侧支持 `prefix.*`)→ 按 `MixinInfo` 逐项 `iterator.remove()`,同 target 其余 mixin 保留("选择性保留"通道,直接服务"在其上继续 mixin"的兼容手术);报告 removed/kept 双记录;
4. **残局字节码**(6.5 残局取证的正解):`MixinCoprocessor` 反射注入(0.15.2:abstract class + package-private 方法 → ASM 生成同包 FQN 子类,`privateLookupIn.defineClass` 主路 + `ClassLoader.defineClass` 兜底,add 进 `MixinProcessor.coprocessors`)→ `postProcess(String, ClassNode)` 在 mixin 应用后、define 前同步回调。观测类声明于 `config/groovier-postwatch.txt`,pre/post 成对落盘 `local/mixin_invalidated/watch/`。

**M3 方向(脚本编排,已定架构)**:类加载早于脚本可用 → **两段式**:运行期脚本读 pre/残局/摘除清单产出"手术包"(manifest 哈希锚定),下次启动由启动脚本在早期同步执行(阶段 2 补丁输出 / postProcess 残局改写 / passthrough 接管三档)。"继续 mixin"首选形态 = 手术级摘除保留存活 mixin,其注册自动叠加到补丁输出。

### 10.2 配置、更新弹窗与完整性扫描(原需求 2)

- **配置时机**:取消"FML 加载前全局配置"路线(early loading display 无交互能力,已调研否决);改为**创建世界界面定制化**。风险:NeoForge 1.21.1 对 `CreateWorldScreen` 无官方扩展 API,需 client mixin(谨慎评估)或退回标题屏按钮入口——实施前 P0 调研项。专用服务器走 config 文件。
- **更新弹窗**:进入标题屏时检测 `.groovy` 兼容性脚本新版本(GitHub 仓库 manifest 比对),弹窗三选:**下载 / 取消 / 再也不提示**(提示入口藏进设置)。下载源 = config 指定的 GitHub 仓库文件夹;落地目录与 `groovy_scripts/` 扫描规则的兼容(排除 refer/;remote 子目录建议)实施时细化。
- **完整性扫描**:比对对象 = **mods 目录 jar + `.groovy` 脚本**;基线 = 同仓 `integrity` 清单(sha256);后台线程执行,**不阻止启动、仅警示**;声明支持开源、抵制篡改。
- 配置作用域:**纯本地**(单机 = 同进程天然生效;联机时客户端配置只影响客户端辅助功能,不要求影响服务端脚本行为,无上行同步协议)。

### 10.3 /groovier register(原需求 3,细化为开发者服务 2026-08-28)

- 命令形态:`/gvr register [type] [filter]`(`/groovier register` 别名):
  - 无参:dump 全部注册表 + `index.csv` 总览;
  - `<type>`:注册表短名(`item`)或完整 key(`minecraft:item`/`neoforge:attachment_type`),仅 dump 该表;
  - `<filter>`:namespace 或路径子串过滤(应对 block 全量 2.6 万条)。
- 覆盖:**全量通用枚举**,不硬编码清单——遍历 `BuiltInRegistries.REGISTRY`(静态)+ `server.registryAccess().registries()`(动态/数据包级:structure/enchantment/biome 等);模组经 `NewRegistryEvent` 自建注册表随根注册表自动覆盖。
- 输出(每表一文件,`local/register/`):
  - `<registry>.json`:表头(mc 版本/时间戳/条目数/registry key)+ `id → {numeric_id, translation_key, java_class, tags[], binds{}}`;
  - `<registry>.csv`(UTF-8 BOM,Excel 友好):`registry, namespace, path, numeric_id, translation_key, java_class, tags, cross_ref`;
  - `index.csv`:全部注册表一览(key/短名/静态或动态/条目数/文件名)。
- 字段增强(开发者高频):Java 类名、tags、交叉引用(item↔block、spawn egg→entity,尽力而为 best-effort)。
- 执行:后台线程 dump(注册表冻结后只读,并发读安全),完成后聊天回执;写入异常单表隔离,不中断其余表。
- 未选(后续按需):`reginfo` 单条查询、`diff` 对比、创造页签归属。
- 已知限制:数字序号为运行时分配,跨安装不稳定,仅作参考;稳定主键为 ResourceLocation。

### 10.4 客户端最小脚本集(原需求 4,端定位翻转)

- **双端允许 `.groovy` 不一致**:客户端脚本引擎首期 = **事件 + 绑定最小集**(ClientTick/渲染帧/GUI/输入等客户端事件 + Log/globals,与现有 EventManager 架构复用);渲染接管/HUD 自绘/音效注入等深度能力不做,后续按需立项。
- 客户端脚本定位:**服务端脚本的辅助/数据源**;客户端→服务端数据通道(自定义负载)列为预留。
- 目录约定:`groovy_scripts/client/` 下的脚本仅客户端执行,其余仅服务端执行(现有服务端扫描规则需加此排除)。
- 失败策略:**出错仅禁用**——编译失败/运行时异常 → 禁用该脚本并告警,单脚本隔离,不影响其他脚本;无版本备份/回滚。
- 沙箱在客户端同等强度;多人场景客户端脚本为本地自有,不从服务端同步(天然允许不一致)。

### 10.5 实施顺序(并入 §6.7 排期)

1. ✅ §10.3 register 命令(M1,2026-08-28 已交付验收;细节见 handoff.md);
2. §10.1 双捕获(mixin 类级作废 + 6.5 冲突诊断)——与 6.3 方法钉子同通道,先行;
3. §10.2 更新弹窗 + 完整性扫描(依赖辅助客户端模块与 GitHub manifest 设计;CreateWorldScreen 入口 P0 调研先行);
4. §10.4 客户端最小脚本集(独立里程碑,风险隔离)。
