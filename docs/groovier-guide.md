# Groovier 使用指南

> **定位**:一个魔改能力较强的**兼容性修复工具**,服务于开发者与整合包维护者。
> 它不是内容开发框架——写配方、做物品请用 KubeJS;Groovier 负责 KubeJS 碰不到的地方:直接修改游戏与模组的 Java 运行时。
> 本指南内置于模组 jar,执行 `/gvr help` 导出到 `local/groovier-guide.md`。

**当前仅服务端生效**,且会修改运行中的字节码——动手前请备份存档。

---

## 1. 能做什么

| 能力 | 一句话说明 | 怎么用 |
|---|---|---|
| Groovy 脚本 | 进存档时执行,可监听事件、调用任何游戏/模组 API | 往 `groovy_scripts/` 放 `.groovy` 文件 |
| 方法钉子 Pins | 拦截任意类的任意方法:抢在它执行前替换结果,或在它执行完后旁听 | `Pins.declare / override / on` |
| mixin 手术 Surgery | 把别人模组打坏的 mixin 摘掉,或直接改某个方法的方法体 | `Surgery.pre / submit` |
| 整类覆盖 Override | 用 Groovy 重写整个类,替换原类 | `groovy_scripts/override/` |
| mixin 摘除 | 按配置文件批量摘除 mixin | `config/groovier-mixin-blacklist.txt` |
| KubeJS 联动 | 脚本拦截到运行时行为后,通知 KubeJS 脚本做内容反应 | `Events.fire` |

## 2. 文件放哪里

```text
groovy_scripts/                  你的脚本(主战场)
  ├─ phase.groovy                顶层脚本:进存档时按相对路径排序逐个执行
  ├─ lib/utils.groovy            子目录自由组织
  ├─ override/                   【保留名】整类覆盖源,不作为普通脚本执行
  └─ refer/                      【保留名】反编译产物(.java/.txt),不会被扫描
config/
  ├─ groovier-mixin-blacklist.txt   摘 mixin 规则(§9)
  ├─ groovier-postwatch.txt         观测类:改动前后各存一份字节,供人工对比
  ├─ groovier-refer.txt             捕获目标类并反编译成源码模板(§11)
  └─ groovier-override.txt          整类覆盖目标(§10)
local/                           全部是产物,可整体删除重建
logs/groovier.log                主日志(每次启动清空)——出问题先看这里
```

- 只认小写 `.groovy`;**相对路径就是脚本的身份**(启停键、缓存键都用它),移动或重命名 = 换了个新脚本;
- 配置文件都是 UTF-8,`#` 注释,空行忽略;blacklist/postwatch/refer **改完必须重启**才生效,畸形条目只会 WARN 跳过、不会炸启动;
- **不要加载来历不明的脚本**(§12)。

## 3. 快速开始

示例:`groovy_scripts/hello.groovy`

```groovy
Log.info("Hello! Level class = {}", Level.class.simpleName)

int n = 0
EventManager.listen { ServerTickEvent.Post event ->
    if (++n % 200 == 0) Log.info("tick #{}", event.server.tickCount)
}
```

进存档(或 `/groovier reload`)后看 `logs/groovier.log`。

## 4. 脚本基础

### 4.1 什么时候执行

- **进存档时(ServerStarting)执行**,主菜单不执行——测脚本必须进世界;
- 按相对路径排序执行;同一轮所有脚本共享一个类加载器,可以互相引用对方定义的类;
- `/groovier reload`:注销全部监听与钉子回调 → 重新编译(内容没变的脚本直接走缓存)→ 重新执行;globals 保留;
- `/gvr disable <脚本>`:立即注销该脚本注册的所有监听器与钉子回调,无需重启。

### 4.2 脚本里能直接用的东西

| 绑定 | 说明 |
|---|---|
| `Log` | 日志,写 `logs/groovier.log` + 控制台 |
| `EventManager` | 监听 NeoForge 事件(§4.3) |
| `globals` | 全局变量:跨脚本共享、reload 保留、可持久化(§4.4) |
| `Events` | 发自定义事件给 KubeJS(§6) |
| `Surgery` | 手术 API(§9) |
| `Pins` | 钉子 API(§8) |

默认已 import(其余类在脚本里自己写 `import`):`Level`、`Block`、`Item`、`ItemStack`、`BlockPos`、`CompoundTag`、`ResourceLocation`、`ServerPlayer`、`Entity`、`Player`、`ServerTickEvent`、`PlayerEvent`。

### 4.3 事件监听怎么写

写法:`EventManager.listen { 事件类型 事件变量 -> ... }`。**闭包里唯一参数的类型就决定了你监听哪个事件**,任意 NeoForge 事件都行,先 `import` 再用:

```groovy
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

EventManager.listen { LivingDeathEvent e ->
    Log.info("{} died", e.entity.name.string)
}
```

**踩雷**:

- 只能监听**具体的事件类**。抽象父类不行——例如 `ServerTickEvent` 必须写它的子类 `ServerTickEvent.Pre` 或 `.Post`;
- 闭包必须有且只有一个参数,且参数类型必须是 Event 的子类,否则注册被拒(日志报错);
- 监听器归属注册它的脚本:脚本被禁用/重载时监听器一并注销,不会残留。

### 4.4 globals 全局变量

```groovy
globals.set('hardMode', true)
def mode = globals.get('hardMode') ?: false
globals.remove('hardMode')
```

- 跨脚本共享、`/groovier reload` 后保留;值为 String/Number/Boolean/List/Map 的键会自动存盘(`local/globals.json`),重启也在;
- `groovier.` 开头的键不存盘(启停键是会话级的,重启回到默认启用);
- **改 List/Map 内容后必须用新引用重新 `set`**,原地改不触发存盘:
  ```groovy
  processed = new ArrayList<>(processed); processed.add(id)
  globals.set('myKey', processed)
  ```
- 值是共享引用,并发修改自己负责;数字存取后整值变 Integer、小数变 Double,比较用 `==`;
- globals 是**全服一个值**,按玩家区分请自己用 UUID 做键。

**脚本动态启停**:每个脚本的开关 = globals 键 `groovier.enabled.<相对路径>`(缺省 = 启用):

```groovy
globals.set('groovier.enabled.hard_mobs.groovy', false)  // 立即禁用该脚本(注销它的监听器)
globals.set('groovier.enabled.hard_mobs.groovy', true)   // 立即执行该脚本(挂载监听器)
```

`/groovier enable|disable <脚本>` 命令等效于改这个键。

### 4.5 典型用法:游戏阶段(类似"肉后"/科技树)

思路:**阶段进度存 globals(持久)**,内容脚本平时禁用,阶段达标后用启停键动态挂载。

```groovy
// phase.groovy(常开,阶段推进器)
def stage = globals.get('phase.stage') ?: 1

// 顶层先对齐当前阶段(幂等,重启后自动校正)
globals.set('groovier.enabled.hard_mobs.groovy', stage >= 2)
globals.set('groovier.enabled.bonus_loot.groovy', stage >= 3)

// 击败凋灵 → 全服进入阶段 2,hard_mobs 立即挂载
EventManager.listen { net.neoforged.neoforge.event.entity.living.LivingDeathEvent e ->
    if (e.entity.type == net.minecraft.world.entity.EntityType.WITHER && !e.entity.level().isClientSide) {
        globals.set('phase.stage', 2)
    }
}
```

```groovy
// hard_mobs.groovy(内容脚本,平时禁用)
// 顶层先自查阶段,防加载时序问题
if ((globals.get('phase.stage') ?: 1) < 2) return
EventManager.listen { ServerTickEvent.Post e -> /* 高压逻辑 */ }
```

多阶段科技树 = 一个 stage 整数 + 每个内容脚本声明自己所属区间。

## 5. 修别的模组:四档工具怎么选

修复目标模组的 bug/冲突时,按侵入度从低到高选,**能用低的绝不用高的**:

| 档位 | 用法 | 适用 |
|---|---|---|
| 事件监听 | 直接写脚本 | 行为能用原生事件表达(监听/取消/修改参数) |
| 方法钉子 | `Pins.declare` + `override/on` | 非事件化的方法调用:前置拦截/替换返回值/旁听 |
| 手术/摘除 | `Surgery.submit` / blacklist | 问题出在**别人的 mixin**(坏 mixin 摘除、改残留字节) |
| 整类覆盖 | `groovy_scripts/override/` | 结构级需求:字段/构造器/类层次(最后手段) |

动手前先摸清目标类,见 §11。

## 6. 与 KubeJS 联动

分工:KubeJS 做内容(配方/物品,快),Groovier 改运行时(深)。Groovier 拦截到运行时行为后发事件,KubeJS 收到后做内容反应:

```groovy
// Groovier 侧
Events.fire('bossSpawned', [boss: 'wither', x: 100, z: -200])
```

```js
// KubeJS 侧 server_scripts
GroovierEvents.fire('bossSpawned', event => {
    console.log(`wither spawned at ${event.data.x}, ${event.data.z}`)
})
```

没装 KubeJS 时 `Events.fire` 是安全空操作(记一条 warn)。

## 7. 命令参考(需 OP,/groovier 与 /gvr 等效)

| 命令 | 作用 |
|---|---|
| reload | 全量热重载(失败脚本跳过并广播) |
| enable / disable \<script\> | 启用/禁用脚本(动态生效) |
| scripts | 脚本列表与启用状态([FAILED] = 编译失败) |
| list / val \<name\> / global | 全局变量:列名 / 看值 / dump 到日志 |
| next \<structure\|#tag\> | 定位未生成区块中的未来结构(点击传送,半径 1600 格) |
| register [type] [filter] | dump 注册表到 local/register/ |
| mixin | 摘除报告(removed/kept/channel_failed) |
| surgery / pins [+ remove] | 手术包/钉子包状态与移除(Tab 补全) |
| override | 覆盖绑定状态(registered/blocked + missing 清单) |
| refer / classtree [过滤] | 反编译导出 / 继承树 |
| help | 命令帮助 + 导出本指南到 local/ |

## 8. 方法钉子(Pins)

拦截任意类的任意方法。两种玩法:

- **override(覆盖)**:闭包返回值直接顶替原方法的返回值,原方法不再执行;
- **on(旁听)**:原方法照常执行,执行完后回调你,只能看不能改。

```groovy
// ① 声明钉子(写入 local/pins/,下次冷启动注入生效)
Pins.declare(name: "zombie-pin",
             target: "net.minecraft.world.entity.monster.Zombie",
             method: "aiStep")                  // 重载方法可加 descriptor: "(J)V" 精确到某一个;不写则同名重载全钉

// ② 覆盖:闭包参数 (thiz, args),thiz = 方法所属对象,args = 参数数组
Pins.override("net.minecraft.world.entity.monster.Zombie.aiStep") { thiz, args ->
    Log.info("zombie tick intercepted")
    return true                                  // 返回值见下方表
}

// ③ 旁听:闭包参数 (thiz, args, result),result = 原方法返回值;override 命中时不触发
Pins.on("net.minecraft.world.entity.monster.Zombie.aiStep") { thiz, args, result ->
    Log.info("aiStep returned {}", result)
}

Pins.list()        // 全部钉子包与注入状态
Pins.remove("zombie-pin")   // 删包,下次启动不再注入
```

**override 闭包该返回什么**:

| 原方法返回类型 | 想拦截(替换返回值) | 想放行(照常执行) |
|---|---|---|
| 对象类型 | return 你要的对象 | return null |
| void | return true(任意非 null 值,包装侧丢弃) | return null |
| 基本类型(int/boolean 等) | return 具体值,**注意 false/0 也算拦截**(它们不是 null) | return null |

**踩雷**:

- declare 只写清单,**必须冷启动**才注入(目标类本轮没被加载过才行);改完 `/gvr reload` 或重启;
- `<init>`/native/abstract/synthetic 方法不可钉;
- 钉子只作用于**目标类自己声明的方法**:子类重写了同名方法时,需要对子类再 declare 一次;
- 高频方法(tick 链)逐帧走闭包,注意性能;
- 目标类被整类覆盖时钉子会被丢弃(report.json 告警)。

> 背后发生了什么:游戏加载目标类之前,原方法被改名为 `groovier$orig$方法名` 并生成一个同名包装方法;包装方法每次执行先查脚本注册的回调表,命中就调你的闭包,查空就调原方法。脚本禁用 = 回调清空 = 方法自动恢复原样,零残留。

## 9. mixin 手术(Surgery)

两件事:**摘 mixin**(把别人打在类上的注入摘掉)和**改方法体**(直接重写字节码)。目标:某个类被 mixin 改坏了,或你需要精确控制某个方法的行为。

**前提**:目标类先配进 `config/groovier-mixin-blacklist.txt` 或 `groovier-postwatch.txt`,启动一次——这样 Groovier 才存有它的字节快照。

```groovy
// 拿到 mixin 之前的原始类骨架(对象类型是 ASM 的 ClassNode)
def node = Surgery.pre("net.minecraft.CrashReport")
if (node == null) { Log.warn("no capture"); return }
// Surgery.post("...") 则是 mixin 应用后的最终形态(需配 postwatch)

Surgery.submit(name: "fix-crashreport",
    target: "net.minecraft.CrashReport",
    mode: "patch_with_mixins",                    // 默认:摘掉 drop 清单,其余 mixin 照常;"exclusive" = 全摘
    drop: ["com.example.BadMixin"]) { n ->
    // 就地修改 n(ASM 树),见下方入门
    n.methods.findAll { it.name == "getDescription" }.each { m ->
        m.instructions.clear()
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0))
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN))
    }
}

Surgery.list()       // 已安装的手术包
Surgery.remove("fix-crashreport")   // 删包,下次启动不生效
```

> 背后发生了什么:提交后产物在 `local/surgeries/<name>/`;下次启动时 Groovier 校验目标类字节和快照一致(防因换版本/换模组打错对象),摘除指定 mixin 后把你的补丁字节作为类加载输入。

### 9.1 ASM 入门:补丁闭包里怎么写字节码

补丁闭包的参数 `n` 是一个 `ClassNode`——目标类被拆成的对象模型:`n.methods` 是方法列表(`MethodNode`),每个方法的方法体是 `m.instructions`(一串指令)。写补丁 = 增删这串指令。

**新手只需掌握三个配方**(改完建议先配 postwatch 观测一轮再实装,§11):

```groovy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

// 配方 A:方法体清空,直接返回固定值(上面示例用的就是它)
// 返回指令必须匹配方法返回类型,见下表
n.methods.findAll { it.name == "isBroken" }.each { m ->
    m.instructions.clear()
    m.instructions.add(new InsnNode(Opcodes.ICONST_0))  // false;ICONST_1 = true
    m.instructions.add(new InsnNode(Opcodes.IRETURN))
}

// 配方 B:方法开头插一句日志/调用,然后继续原逻辑
n.methods.findAll { it.name == "targetMethod" }.each { m ->
    m.instructions.insertBefore(m.instructions.first,
        new MethodInsnNode(Opcodes.INVOKESTATIC,
            "com/bluesky/groovier/hooks/GroovierHooks",   // 类的内部名(点换斜杠)
            "someStaticHook", "()V"))
}

// 配方 C:直接删方法(慎用,调用方会 NoSuchMethodError)
n.methods.removeIf { it.name == "brokenMethod" }
```

**返回指令对照**(配方 A 第二行):

| 方法返回类型 | 压值指令 | 返回指令 |
|---|---|---|
| void | —(不加) | `RETURN` |
| int/boolean/byte/short/char | `ICONST_0/1` 等 | `IRETURN` |
| long / float / double | `LCONST_0` / `FCONST_0` / `DCONST_0` | `LRETURN` / `FRETURN` / `DRETURN` |
| 任意对象 | `ACONST_NULL` 或 `new LdcInsnNode("...")` 装常量 | `ARETURN` |

更复杂的字节码编辑(分支、局部变量、异常表)超出本指南范围——先跑 `/gvr refer` 拿到反编译源码当参照(§11),再对照写。

**踩雷**:

- **类级全摘(blacklist 裸类名)是核弹**:该类上所有模组的 mixin 一并消失,依赖它的模组可能直接崩;被硬依赖的基类只用手术级 `target::mixin` 精摘;
- blacklist 里 `mode`/条目拼错会拒绝加载并告警,不静默降级;
- 手术包随 jar 分发时,目标类字节一变(游戏换版本)即失效拒绝——这是防错杀的特性,不是 bug。

## 10. 整类覆盖(Override)

最后手段:用 Groovy 重写整个类,替换原类。适合字段/构造器/类层次级的问题——钉子和手术都够不着时才用。

**流程**:

1. `config/groovier-refer.txt` 写目标类名 → 启动一次 → `/gvr refer` 反编译出源码模板(在 `groovy_scripts/refer/`);
2. 模板复制为 `groovy_scripts/override/<类名>.groovy`,做修改;
3. `config/groovier-override.txt` 写同类名,重启 → Groovier 编译你的覆盖源,并与原类做**契约校验**(比对双方对外可见的成员:public/protected/包级方法与字段、继承关系);
4. 校验通过 → 类加载前整类替换;不通过 → 状态 blocked,`/gvr override` 会列出缺失成员,补齐后 `/groovier reload` 重绑。

覆盖源长这样:

```groovy
// groovy_scripts/override/ExampleClass.groovy
// package + class 声明必须与目标类完全同名,否则拒绝应用
package com.example

class ExampleClass extends ExampleBase {
    // 原类的构造器、字段、对外方法都要复刻(签名一致)
    // refer 反编译产物就是最好的参照——照着它改
}
```

**踩雷**:

- 整类替换 = **放弃其他模组对这个类的一切 mixin/AT 改动**。refer 产物里如果混有存活 mixin 的行为,契约校验会逼你把它们复刻进覆盖类;
- 已加载的类本轮不变,覆盖以冷启动为准;
- Groovy 不允许同名方法 private/public 混用重载;删掉的方法就是真删了(契约会拦)。

## 11. 动手前:摸清目标类

写手术/钉子/覆盖之前,先搞清三件事:**类长什么样、方法签名是什么、被谁 mixin 过**。

| 想知道什么 | 怎么拿 |
|---|---|
| 类的最终源码(含 mixin 改动后) | `config/groovier-refer.txt` 写类名 → 启动一次 → `/gvr refer` → `groovy_scripts/refer/<类>.java` |
| 继承关系(父类/接口/子类) | `/gvr classtree <包名过滤>` |
| 类被哪些 mixin 改过 | 解压目标模组 jar,看 `*.mixins.json`:把 `"package"` 与 `"mixins"`/`"client"` 数组拼起来就是完整 mixin 类名 |
| 改动前后字节对比 | `config/groovier-postwatch.txt` 写类名 → 启动进存档 → `local/mixin_invalidated/watch/pre|post/` 成对字节,反编译对比 |
| 摘除最终结果 | `/gvr mixin` 命令 + `local/mixin_invalidated/report.json`(**最终事实**) |

标准工作流:refer 反编译 → 定位问题方法 → 选档(§5)→ 先配 postwatch 观测一轮确认理解无误 → 再实装。

## 12. 沙箱规则(什么会被拦)

脚本跑在沙箱里,防的是"脚本把服务器搞崩"。以下**编译直接报错**:

- 起进程:`"...".execute()`、`Runtime`、`ProcessBuilder`;
- 系统:`System.exit/gc/setSecurityManager/load/loadLibrary`;
- 类加载器:`ClassLoader`、`GroovyClassLoader`、`GroovyShell` 等(含 Groovier 自身组件);
- 网络:`java.net`、`javax.net`、`java.nio.channels`、`java.rmi`、`com.sun.`、`sun.` 全家;
- GString 动态方法名:`obj."$name"()` 一律拒绝;
- 依赖拉取:`@Grab` 禁用。

**允许**:文件 IO、反射、访问任意模组类、修改不在黑名单里的 metaClass。

报错信息含 `blacklist` 字样 = 沙箱拦截,是预期行为,不是 bug。

> **信任模式警示**:沙箱不是安全隔离——文件读写与反射仍然自由。**只加载你信任来源的脚本**。
