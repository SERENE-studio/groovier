# groovier handoff(2026-08-30,6.4 整类覆盖 T29-T33 实机验收全通过)

## 当前进度

- 总体排期:M1 ✅ / M2 ✅ / M3 ✅ / 6.3 方法钉子原身通路 ✅ / 6.5 反编译导出工具链 ✅ / **6.4 整类覆盖 ✅(交付 + 实机验收 T29-T33 全通过)**/ M4-M6 待做(§10.2/10.4)。
- **6.4 已交付并验收**(SPEC §6.4 含"验收实证"节,测试指导 §10 含验收记录):ReferClasstree 复刻全链替换生效(10 methods/4 fields,OVERLIVE 标记);契约阻止/reload 重绑定/钉子冲突告警均实证。测试遗留:override.txt 的 ReferClasstree 目标与 `override/ReferClasstree.groovy` 复刻**有意保留**(活的示范用例,classtree 输出带 [OVERLIVE] = 覆盖生效常态信号);T33 测试钉子包与脚本已移 `waste/`。
- **机制性发现(实证,勿回退,详见 SPEC §6.4 验收实证)**:
  1. **覆盖类必须强制 @CompileStatic**(OverrideManager 编译配置注入 `ASTTransformationCustomizer(CompileStatic)`):动态 Groovy 的 `$_closure` 辅助类游戏加载器加载不到(执行到闭包路径 NCDFE);动态分派触发全局沙箱守卫;CompileStatic 下 lambda 走 indy 无辅助类。复刻源须适配静态编译(`Files.walk(...).toList()` 替代 for-each Stream;禁止跨行 `+` 开头拼接);
  2. **沙箱运行期守卫按加载器划作用域**(GrSMetaClassCreationHandle.isSandboxWorld):JDK 类(null 加载器)始终守卫、GroovierClassLoader 体系守卫、游戏加载器可信类放行——否则覆盖类对 `sun.nio.fs.WindowsPath` 的合法分派被黑名单误伤;
  3. **FQN 归一化**:ClassReader 的 `ClassNode.name` 是斜杠内部名,OverrideStore 比对前必须转点号(T30 首轮 fail-safe 拒换实证);
  4. **MDG dev 运行层清单只收 additionalRuntimeClasspath**(6.5 轮发现,继续有效):运行期 jar 依赖三件套 `implementation + additionalRuntimeClasspath + jarJar`。
- **6.4 已交付(SPEC §6.4 落地状态,测试指导 §10 T29-T33)**:
  - 配置 `config/groovier-override.txt`(语法同 refer:精确类名 + `prefix.*`)= 整类替换白名单,覆盖产物 FQN 必须命中才注册(防同名类劫持);
  - GAME 层新包 `com.bluesky.groovier.override`:`OverrideManager`(主模组构造期 + `/groovier reload` 后台重跑;**影子编译**:目标类解析优先取 `local/refer/classes/` 残局字节、其次父加载器 resource 原始字节,就地 define 不触发目标类真实加载——否则 coprocessor 回调在绑定落盘前发生,替换永久错过)→ `ApiDiff`(6.4.2 防线 3:public/protected 缺失 = 阻止,private 缺失 = WARN[nestmate 风险],`groovier$`/`<clinit>`/synthetic/bridge 不计契约)→ 通过者落盘 `local/override/classes/<fqn>.class`(快照化)+ `local/override/bind.txt`;
  - coremod 侧:`OverrideStore`(coprocessor 回调整类替换:override 字节 parse → 结构整体换入残局 node;类名保留;**自引用超类修正**(覆盖类 extends 目标自身 → 改指原父类);nestMembers 保留原清单保 nestmate 访问;fail-safe 备份恢复)+ `OverrideTransformer`(独立触发器;前缀展开无果时锚定 MinecraftServer 兜底);`dispatchPost` 三消费 PostWatch → OverrideStore → ReferStore(顺序保证 refer 取证替换前字节);
  - report.json 新增 `overrides` 块;`/gvr override` 清单(bind × 注入状态);ScriptManager 顶层 `override/`/`refer/` 目录排除出脚本扫描;
  - **与钉子互斥**:pre-mixin 钉子包装会被整类替换丢弃,OverrideStore 检测到即告警;复刻类保留包装则 `Pins.override` 回调表仍生效。
- 6.5 状态(T22-T28 已验收)见上轮记录:启动导出 58 files、classtree 59 classes 等。

## 已通过测试

- `.\gradlew.bat build coremodDevJar` 通过(2026-08-30,6.4 验收后终版);构建前关闭客户端(运行时锁 run/mods jar 与 neoforge artifacts jar);`$env:GRADLE_USER_HOME='D:\bluesky_fantaste\.gradle-home'`;
- M1-M3(T0-T13)、6.3(T14-T21)、6.5(T22-T28)此前已验收;**6.4(T29-T33)2026-08-30 全通过**:T29 无配置回归(overrides 块空)/ T30 全链替换 + OVERLIVE 标记(全量与 Zombie 过滤均验)/ T31 契约阻止(精确报 missing dump(String),落盘无产物,原身行为)/ T32 reload 重绑定(registered,已加载类保持原身 = 机制边界确认)/ T33 report.json `applied; WARNING 1 pin pack(s) exist but will be discarded` + log 告警 ✅。详见测试指导 §10 验收记录。

## 实现要点与坑(勿重蹈)

1. **跨层通道只用文件产物**:coremod(SERVICE 层副本)与 GAME 层同名 jar 是两个模块/类空间,静态桥不通(PinningTransformer 的 GroovierHooks 按名引用能通是因为解析发生在目标类加载器);override 绑定走 `local/override/classes/*.class` 文件,与 pins/surgeries 同构;
2. **Javadoc 里写 `**/*.groovy` 会提前终止注释**(`*/` 在其中),编译报一串中文乱码"需要 class/interface"——通配描述改写避开 `*/`;
3. ASM ClassNode 字段名:`nestMembers`/`nestHostClass`(不是 nestHost);
4. `List.of(new String[]{...})` 会被解释为 varargs 产生 `List<String>`,需 `List.<String[]>of(...)`;
5. **影子加载器必须 override `loadClass`**(parent-first 下 findClass 永远轮不到:游戏类父加载器总能给)——refer-first 就地 define,编译期零真实加载;
6. mix-in 替换语义:自引用超类(`class Zombie extends Zombie` 复刻写法)在 swap 时修正为原 superName;nestMembers 不保留会导致原内部类 nestmate 访问 IllegalAccessError。

## 本轮验收实录(三轮启动定位 + 修复,勿重蹈)

1. **T30 首轮 fail-safe 拒换**:`FQN mismatch expected com.bluesky... got com/bluesky/...` —— ClassNode.name 是内部名,比对前 `replace('/', '.')` 修复;
2. **closure 辅助类 NCDFE 风险**:动态 Groovy 把 lambda 编译为 `$_dump_closure1-4` 辅助类,bind 时被 skip,运行时覆盖类执行到闭包路径必炸(游戏加载器无此类)——先改写为无闭包版本,后以强制 CompileStatic 根治(indy lambda);
3. **沙箱守卫误伤覆盖类**:`SandboxSecurityException: sun.nio.fs.WindowsPath is blacklisted` —— 全局 MetaClassRegistry 句柄按加载器划作用域修复(JDK 类仍守卫);第一版作用域判断不够:WindowsPath 本身是 JDK 类,静态编译才是覆盖类正解;
4. **Groovy 语法坑**:同名方法禁止 private/public 重载混用(compile-error,不是 blocked——T31 用例改用"真删方法");CompileStatic 下跨行 `+` 开头拼接报 `String#positive()`;
5. **StopCommand 残留进程锁构建**:残留客户端窗口(命令行不含 forgeclientdev,需按窗口标题 "Minecraft NeoForge*" 识别)锁 `build/moddev/artifacts/neoforge-*.jar` → 构建 AccessDeniedException;先 `Stop-Process` 再构建;
6. report.json 的 overrides 块为**懒写**(目标类加载时才 record + 重写文件),启动后未触发类加载时内容可能滞后于 bind.txt——排查时以 bind.txt + latest.log 为准。

## 已知问题与风险

- 6.4 机制边界:替换 = 编译期快照,已加载类不可再替换(reload 仅刷新后续懒加载类);整类替换丢弃目标类上其他模组 mixin/AT(6.4.1 明示取舍,ApiDiff 会逼出缺失成员复刻);
- 基准降级(refer 未捕获 → jar 原始字节)时,其他模组 mixin 改动不在基准内,bind 行标 `baseline=original-jar` 需人工复核;
- coprocessor postProcess 返回 true 后 mixin 侧的后续处理(是否重算帧)未深究——override 方法节点自带完整帧,实测 T30 是检验点,异常会以 fail-safe 原类加载呈现(report 标 error);
- 被钉类的同类内部调用经包装走钩子(6.3 遗留);refer 产物含钉子包装字节(6.4 复刻时 `groovier$` 不计契约,包装可整体去留);
- 偶发(待观察):`FMLConfig$ConfigValue.getConfigValue` NPE,与 Groovier 无关。

## 下一步

1. 6.3 残局通路(coprocessor 注钉)与继承树自动遍历(基于 classtree 基建);
2. §10.2(配置/更新弹窗/完整性扫描,P0 调研 CreateWorldScreen);§10.4(客户端最小脚本集)。
3. 6.4 后续增强(可选):契约告警级(WARN)明细进 bind 行;refer 模板 → 复刻的半自动转换脚本(注意 CompileStatic 适配)。

**方向评估(2026-08-30 收工时定,下次会话勿盲目按上表推进)**:核心工具链(钉子→手术→refer→整类覆盖)已闭环,排期剩余项均为功能扩张而非必需修补——残局注钉改为按需触发(实证到"钉子打在被 mixin 改动的类上语义不对"再做);继承树自动遍历降级(与加载前替换有机制张力,前缀通配已覆盖大部分场景);§10.2/§10.4 是整合包分发场景的产品功能,由用户决策优先级。**建议转入使用驱动**:拿真实目标类跑一遍完整流程,以真实摩擦牵引下一步。

## 移交须知

- 驾驭文档:`文档\开工\(极重要)workflow工作流.md`;SPEC:`groovier\SPEC.md`(§2.6/§6.1/§6.4/§6.5/§6.7 已同步);测试:`groovier\docs\测试指导-mixin手术.md` §10(T29-T33);本文件为进度交接;
- 用户规则:中文回复、英文标点、执行命令先讲用意与风险、删除文件移 `waste/`、不启动专用服务器、python 用 conda;
- 工程注意:沙箱限制 `D:\.gradle` 写入 → GRADLE_USER_HOME 重定向;PowerShell 输出中文乱码但文件为 UTF-8 正常;**StopCommand 后确认客户端窗口真的关了**——残留窗口进程命令行不含 forgeclientdev,按窗口标题 "Minecraft NeoForge*" 识别(Get-CimInstance Win32_Process 匹配 CommandLine 会漏),否则锁构建 artifacts jar;dev 实机测试 jar 手动复制 `build/libs/groovier-0.1.0-coremod-dev.jar` 进 `run/mods` 并与构建同步;
- 观测锚点:override 相关 logger = `com.bluesky.groovier.override.OverrideManager` / `com.bluesky.groovier.coremod.OverrideStore`,groovier.log 与 latest.log 可见。
