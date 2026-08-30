package com.bluesky.groovier.sandbox;

import com.bluesky.groovier.Groovier;
import com.bluesky.groovier.api.GlobalManager;
import com.bluesky.groovier.api.GroovierEventsBridge;
import com.bluesky.groovier.api.GroovyLog;
import com.bluesky.groovier.api.SurgeryApi;
import com.bluesky.groovier.engine.GroovierClassLoader;
import com.bluesky.groovier.event.GroovierEventManager;
import com.bluesky.groovier.sandbox.security.GrSMetaClassCreationHandle;
import com.bluesky.groovier.sandbox.transformer.GroovierCompiler;
import com.mojang.datafixers.util.Pair;
import groovy.lang.Binding;
import groovy.lang.GroovySystem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import groovy.transform.CompileStatic;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 脚本沙箱:全局绑定(Log/EventManager/globals)、默认 import、编译配置与执行编排。
 * M3:支持全量 reload(/groovier reload)与 globals 联动的脚本动态启停。
 * M4:沙箱安全(黑名单类/方法 + @GroovyBlacklist),编译期 AST + 运行时 MetaClass 双通道拦截。
 */
public class GroovierSandbox {

    private final Map<String, Object> bindings = new HashMap<>();
    private final ImportCustomizer importCustomizer = new ImportCustomizer();
    private final ScriptManager scriptManager = new ScriptManager();

    public GroovierSandbox() {
        // 注入运行时沙箱:黑名单类 → 空 MetaClass(调用即抛异常)
        GroovySystem.getMetaClassRegistry().setMetaClassCreationHandle(GrSMetaClassCreationHandle.INSTANCE);
        // EMC(ExpandoMetaClass)保持可用,支撑"脚本修改运行时类"(Groovy 侧方法覆盖)愿景;黑名单类仍由 GrSMetaClassCreationHandle 优先拦截
        registerBinding("Log", GroovyLog.INSTANCE);
        registerBinding("EventManager", GroovierEventManager.INSTANCE);
        registerBinding("globals", new com.bluesky.groovier.api.GlobalView(GlobalManager.INSTANCE));
        // KubeJS 联动:Events.fire("name", [key: value]) 触发 KubeJS 事件(可选依赖,未加载时 no-op)
        registerBinding("Events", GroovierEventsBridge.INSTANCE);
        // 手术 API:pre/post/submit/list/remove(运行期开发,冷启动生效)
        registerBinding("Surgery", SurgeryApi.INSTANCE);
        // 钉子 API:declare/override/on/list/remove(运行期声明冷启动注入;覆盖表运行期生效)
        registerBinding("Pins", com.bluesky.groovier.api.PinsApi.INSTANCE);
        addDefaultImports();
    }

    /** 执行脚本(ServerStarting 首次与 /groovier reload 共用)。全局变量(globals)在 reload 间保留。 */
    public void run() {
        long start = System.currentTimeMillis();
        File root = getScriptRoot();
        // 自动创建脚本目录,避免首启时目录缺失导致脚本跳过(目录名独立,防止与其他模组的 scripts 目录撞车)
        if (!root.isDirectory() && !root.mkdirs()) {
            GroovyLog.INSTANCE.warn("Failed to create Groovier scripts directory {}", root.getAbsolutePath());
        }
        GroovierClassLoader loader = new GroovierClassLoader(Groovier.class.getClassLoader(), createConfig());
        scriptManager.runAll(loader, new Binding(bindings), root);
        GroovyLog.INSTANCE.info("Groovier scripts finished in {} ms, {} listeners active.",
                System.currentTimeMillis() - start, GroovierEventManager.INSTANCE.listenerCount());
    }

    /**
     * 命令:后台全量重载(层2)。prepare(扫描+增量编译,层1缓存命中时直接 defineClass)在后台线程,
     * 完成后经 server.execute 把 apply 投递回 server thread(注销旧监听 + 执行脚本),命令立即返回不阻塞 tick。
     */
    public void reloadAsync(CommandSourceStack source) {
        File root = getScriptRoot();
        // 自动创建脚本目录,避免脚本目录缺失导致扫描为空
        if (!root.isDirectory() && !root.mkdirs()) {
            GroovyLog.INSTANCE.warn("Failed to create Groovier scripts directory {}", root.getAbsolutePath());
        }
        if (!scriptManager.tryBeginReload()) {
            source.sendSuccess(() -> Component.literal("Groovier reload already in progress, ignoring."), true);
            return;
        }
        // 在命令线程(server thread)捕获 server 引用与绑定模板;后台线程只碰文件与类加载器,不读服务器状态
        final MinecraftServer server = source.getServer();
        final Binding template = new Binding(bindings);
        source.sendSuccess(() -> Component.literal("Groovier is reloading scripts in the background..."), true);
        CompletableFuture.runAsync(() -> {
            try {
                // 6.4:重编译 override 源并刷新落盘(已加载类无法再替换,属机制边界)
                com.bluesky.groovier.override.OverrideManager.rebind();
                GroovierClassLoader loader = new GroovierClassLoader(Groovier.class.getClassLoader(), createConfig());
                ScriptManager.ReloadResult result = scriptManager.prepare(loader, template, root);
                if (server == null || server.isStopped()) {
                    GroovyLog.INSTANCE.warn("Server stopped during background reload, discarding result.");
                    scriptManager.endReload();
                    return;
                }
                // apply 必须回 server thread(事件总线注销与脚本执行不跨线程)
                server.execute(() -> {
                    try {
                        scriptManager.apply(result);
                        broadcastReloadResult(server, result.failed);
                    } catch (Throwable t) {
                        GroovyLog.INSTANCE.error("Failed to apply Groovier reload: {}", t);
                        broadcastReloadResult(server, List.of("apply failed: " + t.getMessage()));
                    } finally {
                        scriptManager.endReload();
                    }
                });
            } catch (Throwable t) {
                GroovyLog.INSTANCE.error("Groovier background reload failed: {}", t);
                scriptManager.endReload();
            }
        });
    }

    /** reload 结束动作:向所有在线玩家与控制台广播结果,并写入 groovier.log。 */
    private void broadcastReloadResult(MinecraftServer server, List<String> failed) {
        String msg = failed.isEmpty() ? "reload succeed" : "reload succeed except " + String.join(", ", failed);
        server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(Component.literal(msg)));
        server.sendSystemMessage(Component.literal(msg));
        GroovyLog.INSTANCE.info(msg);
    }

    /** 命令:启用/禁用脚本。返回反馈消息。 */
    public String setScriptEnabled(String name, boolean enabled) {
        // 同步编译执行:大脚本会短暂阻塞 server thread,正确性优先的取舍
        // L12:用 resolveScript 返回值判定,不再依赖错误消息前缀字符串
        String resolved = scriptManager.resolveScript(name);
        if (resolved == null) {
            return scriptManager.resolveScriptOrError(name);
        }
        GlobalManager.INSTANCE.set(ScriptManager.ENABLED_PREFIX + resolved, enabled);
        return (enabled ? "Enabled " : "Disabled ") + resolved;
    }

    /** 命令:列出脚本及其状态(三色:FAILED 红 / ENABLED 绿 / DISABLED 紫),脚本名点击可复制。 */
    public void listScripts(CommandSourceStack source) {
        for (ScriptManager.ScriptState state : scriptManager.getScripts()) {
            ChatFormatting color;
            String tag;
            if (state.failed) {
                color = ChatFormatting.RED;
                tag = "[FAILED]";
            } else if (state.enabled) {
                color = ChatFormatting.GREEN;
                tag = "[ENABLED]";
            } else {
                color = ChatFormatting.LIGHT_PURPLE;
                tag = "[DISABLED]";
            }
            String logLine = tag + " " + state.relPath;
            source.sendSuccess(() -> Component.literal(tag + " ").withStyle(color)
                    .append(Component.literal(state.relPath).withStyle(s -> s.withColor(color)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, state.relPath))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击复制 / Click to copy"))))), false);
            GroovyLog.INSTANCE.info(logLine); // 日志行保持纯文本,含 [FAILED] 标签即可
        }
    }

    /** 命令:列出全部全局变量名(点击变量名复制键名)。 */
    public void listGlobals(CommandSourceStack source) {
        Map<String, Object> globals = GlobalManager.INSTANCE.asMap();
        if (globals.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No global variables."), false);
            return;
        }
        globals.keySet().stream().sorted().forEach(key ->
                source.sendSuccess(() -> Component.literal(key).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, key))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击复制 / Click to copy")))), false));
    }

    /** 命令:val - 打印全局变量的值。 */
    public String getGlobalValue(String name) {
        GlobalManager gm = GlobalManager.INSTANCE;
        if (!gm.contains(name)) return "Global variable not found: " + name;
        return name + " = " + String.valueOf(gm.get(name));
    }

    /** 命令:next - 在最近的"未生成区块"中定位结构(精确 key 或 #tag,跳过已生成区块)。 */
    public void locateNextStructure(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> holderSet;
        if (name.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(name.substring(1));
            if (tagId == null) {
                source.sendFailure(Component.literal("Invalid structure tag: " + name));
                return;
            }
            Optional<HolderSet.Named<Structure>> tag = registry.getTag(TagKey.create(Registries.STRUCTURE, tagId));
            if (tag.isEmpty()) {
                source.sendFailure(Component.literal("Unknown structure tag: " + name));
                return;
            }
            holderSet = tag.get();
        } else {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) {
                source.sendFailure(Component.literal("Invalid structure name: " + name));
                return;
            }
            Optional<Holder.Reference<Structure>> holder = registry.getHolder(id);
            if (holder.isEmpty()) {
                source.sendFailure(Component.literal("Unknown structure: " + name));
                return;
            }
            holderSet = HolderSet.direct(holder.get());
        }
        // skipKnownStructures=true:仅返回尚未生成区块中的结构(与需求"仅未生成"一致);半径 100 区块 = 1600 格,同 /locate
        BlockPos center = BlockPos.containing(source.getPosition());
        Pair<BlockPos, Holder<Structure>> pair = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, holderSet, center, 100, true);
        if (pair == null) {
            source.sendFailure(Component.literal("No " + name + " found within 1600 blocks."));
            return;
        }
        BlockPos pos = pair.getFirst();
        String display = pair.getSecond().getRegisteredName();
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        // 与 /locate 一致用 "~" 高度,点击 RUN_COMMAND 直接传送(不落地于 y=0,也不强制生成区块算高度)
        Component coords = ComponentUtils.wrapInSquareBrackets(Component.literal(pos.getX() + ", ~, " + pos.getZ()))
                .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp @p " + pos.getX() + " ~ " + pos.getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击传送 / Click to teleport"))));
        source.sendSuccess(() -> Component.literal(display + " @ ").append(coords)
                .append(Component.literal(" (" + dist + " blocks away)")), false);
        GroovyLog.INSTANCE.info("Located {} at [{}, {}, {}] ({} blocks away)", display, pos.getX(), pos.getZ(), dist);
    }

    /** 命令:help - 中英双语用法,按客户端语言切换(zh 中文,其余英文)。 */
    private static final String[][] HELP_ENTRIES = {
            {"/groovier reload", "全量热重载脚本(后台执行,完成后广播结果)", "Reload all scripts in background, result broadcast on finish"},
            {"/groovier enable <script>", "启用脚本", "Enable a script"},
            {"/groovier disable <script>", "禁用脚本", "Disable a script"},
            {"/groovier list", "列出全部全局变量名(点击复制)", "List all global variable names (click to copy)"},
            {"/groovier scripts|script", "列出脚本及状态(点击复制)", "List scripts and status (click to copy)"},
            {"/groovier val <name>", "打印全局变量的值", "Print a global variable's value"},
            {"/groovier global", "将全部全局变量写入 groovier.log", "Dump all global variables to groovier.log"},
            {"/groovier next <structure|#tag>", "定位最近的未生成区块中的结构(点击坐标传送)", "Locate nearest structure in ungenerated chunks (click coords to teleport)"},
            {"/groovier register [type] [filter]", "dump 注册表到 local/register/(json+csv;type 如 item,filter 按 namespace/路径子串过滤)", "Dump registries to local/register/ (json+csv; e.g. type=item, filter by namespace/path substring)"},
            {"/groovier mixin", "mixin 类级作废报告(local/mixin_invalidated/)", "Mixin invalidation report (local/mixin_invalidated/)"},
            {"/groovier surgery [remove <name>]", "手术包清单/删除(Surgery.submit 产物,冷启动生效)", "Surgery packs list/remove (effective on next launch)"},
            {"/groovier pins [remove <name>]", "钉子包清单/删除(Pins.declare 产物,冷启动注入)", "Pin packs list/remove (effective on next launch)"},
            {"/groovier override", "整类覆盖绑定清单(绑定 + 注入状态,产物 local/override/)", "Class override bindings (bind + injection status, artifacts local/override/)"},
            {"/groovier refer", "后台反编译导出 refer 类(local/refer/classes → groovy_scripts/refer/)", "Decompile captured refer classes to groovy_scripts/refer/ in background"},
            {"/groovier classtree", "生成 refer 继承树(groovy_scripts/refer/classtree.txt)", "Generate refer class hierarchy (groovy_scripts/refer/classtree.txt)"},
            {"/groovier help", "显示本帮助", "Show this help"},
    };

    public void showHelp(CommandSourceStack source) {
        String lang = source.getEntity() instanceof ServerPlayer sp ? sp.clientInformation().language() : "en_us";
        boolean zh = lang.toLowerCase().startsWith("zh");
        source.sendSuccess(() -> Component.literal(zh
                ? "=== Groovier 命令(前缀 /groovier 或 /gvr) ==="
                : "=== Groovier Commands (prefix /groovier or /gvr) ==="), false);
        for (String[] entry : HELP_ENTRIES) {
            String usage = entry[0];
            String desc = zh ? entry[1] : entry[2];
            source.sendSuccess(() -> Component.literal(usage + " - " + desc), false);
        }
    }

    /** 命令:将所有全局变量 dump 到 groovier.log。 */
    public void dumpGlobals() {
        for (Map.Entry<String, Object> entry : GlobalManager.INSTANCE.asMap().entrySet()) {
            GroovyLog.INSTANCE.info("[global] {} = {}", entry.getKey(), String.valueOf(entry.getValue()));
        }
    }

    private CompilerConfiguration createConfig() {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setSourceEncoding("UTF-8");
        // 禁用 Grape 依赖拉取:脚本不允许引入外部依赖
        config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        // 编译期沙箱拦截:黑名单类/方法直接编译报错
        config.addCompilationCustomizers(new GroovierCompiler());
        config.addCompilationCustomizers(importCustomizer);
        return config;
    }

    /** 编译配置(6.4 整类覆盖:可信复刻,无沙箱守卫,强制 @CompileStatic)。 */
    public CompilerConfiguration compilerConfig() {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setSourceEncoding("UTF-8");
        config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        // 不带 GroovierCompiler(编译期黑名单会误伤可信复刻的合法 API 面)
        config.addCompilationCustomizers(importCustomizer);
        // 强制静态编译:直接调用,零 MOP/零 meta class 查询——运行期全局沙箱守卫
        // (GrSMetaClassCreationHandle 对 JDK 类始终生效)不会拦截覆盖类的动态分派;
        // 且 lambda 走 invokedynamic,不再生成 $_closure 辅助类(游戏加载器无法加载)
        config.addCompilationCustomizers(new ASTTransformationCustomizer(CompileStatic.class));
        return config;
    }

    private void addDefaultImports() {
        importCustomizer.addImports(
                "net.minecraft.world.level.Level",
                "net.minecraft.world.level.block.Block",
                "net.minecraft.world.item.Item",
                "net.minecraft.world.item.ItemStack",
                "net.minecraft.core.BlockPos",
                "net.minecraft.nbt.CompoundTag",
                "net.minecraft.resources.ResourceLocation",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.world.entity.Entity",
                "net.minecraft.world.entity.player.Player",
                "net.neoforged.neoforge.event.tick.ServerTickEvent",
                "net.neoforged.neoforge.event.entity.player.PlayerEvent");
    }

    public void registerBinding(String name, Object obj) {
        bindings.put(name, obj);
    }

    public File getScriptRoot() {
        // 脚本根目录:游戏目录(GAMEDIR)下的 groovy_scripts/(独立命名,避免与其他模组的 scripts 目录撞车;
        // 不可用进程 cwd——托管面板自定义启动目录时 cwd ≠ GAMEDIR)
        return new File(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toFile(), "groovy_scripts");
    }
}
