package com.bluesky.groovier.command;

import com.bluesky.groovier.Groovier;
import com.bluesky.groovier.registry.RegistryDumper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Groovier 命令组,支持 /groovier 与 /gvr 双前缀:
 *  reload                 - 后台全量热重载脚本(完成后广播结果)
 *  enable/disable <script> - 启用/禁用脚本(状态存 globals,动态生效)
 *  list                   - 列出全部全局变量名(点击可复制)
 *  scripts|script         - 列出脚本及启用状态(点击可复制)
 *  val <name>             - 打印全局变量的值
 *  next <structure|#tag>  - 在最近的"未生成区块"中定位结构(点击坐标传送)
 *  register [type] [filter] - dump 注册表到 local/register/(json+csv,静态+动态,支持过滤)
 *  global                 - 将当前所有全局变量 dump 到 groovier.log
 *  classtree              - refer 继承树 → groovy_scripts/refer/classtree.txt
 *  refer                  - 后台反编译导出 refer 类(local/refer/classes → groovy_scripts/refer/)
 *  help                   - 中英双语用法(按客户端语言切换)
 */
public class GroovierCommand {

    /** remove <name> 的 Tab 建议:列出已安装的手术包名 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SURGERY_NAMES = (ctx, builder) -> {
        com.bluesky.groovier.api.SurgeryApi.INSTANCE.list().keySet().forEach(builder::suggest);
        return builder.buildFuture();
    };

    /** remove <name> 的 Tab 建议:列出已安装的钉子包名 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PIN_NAMES = (ctx, builder) -> {
        com.bluesky.groovier.api.PinsApi.INSTANCE.list().keySet().forEach(builder::suggest);
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildTree("groovier"));
        dispatcher.register(buildTree("gvr"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTree(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    // 后台重载:prepare 在后台线程,apply 回 server thread,命令立即返回
                    Groovier.getSandbox().reloadAsync(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("enable")
                        .then(Commands.argument("script", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "script");
                            String msg = Groovier.getSandbox().setScriptEnabled(name, true);
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                            return 1;
                        })))
                .then(Commands.literal("disable")
                        .then(Commands.argument("script", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "script");
                            String msg = Groovier.getSandbox().setScriptEnabled(name, false);
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                            return 1;
                        })))
                .then(Commands.literal("list").executes(ctx -> {
                    Groovier.getSandbox().listGlobals(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("scripts").executes(GroovierCommand::runListScripts))
                .then(Commands.literal("script").executes(GroovierCommand::runListScripts))
                .then(Commands.literal("global").executes(ctx -> {
                    Groovier.getSandbox().dumpGlobals();
                    ctx.getSource().sendSuccess(() -> Component.literal("Global variables dumped to groovier.log"), true);
                    return 1;
                }))
                .then(Commands.literal("val")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String msg = Groovier.getSandbox().getGlobalValue(name);
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                            return 1;
                        })))
                .then(Commands.literal("next")
                        .then(Commands.argument("structure", StringArgumentType.greedyString()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "structure");
                            Groovier.getSandbox().locateNextStructure(ctx.getSource(), name);
                            return 1;
                        })))
                .then(Commands.literal("register")
                        .executes(ctx -> {
                            // 无参:dump 全部注册表(静态 + 动态)到 local/register/
                            RegistryDumper.dump(ctx.getSource(), null, null);
                            return 1;
                        })
                        .then(Commands.argument("type", StringArgumentType.string())
                                .executes(ctx -> {
                                    RegistryDumper.dump(ctx.getSource(), StringArgumentType.getString(ctx, "type"), null);
                                    return 1;
                                })
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            RegistryDumper.dump(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "type"),
                                                    StringArgumentType.getString(ctx, "filter"));
                                            return 1;
                                        }))))
                .then(Commands.literal("mixin").executes(ctx -> {
                    runMixinReport(ctx);
                    return 1;
                }))
                .then(Commands.literal("surgery")
                        .executes(ctx -> {
                            runSurgeryList(ctx.getSource(), null);
                            return 1;
                        })
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SUGGEST_SURGERY_NAMES)
                                        .executes(ctx -> {
                                            runSurgeryList(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        }))))
                .then(Commands.literal("pins")
                        .executes(ctx -> {
                            runPinsList(ctx.getSource(), null);
                            return 1;
                        })
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SUGGEST_PIN_NAMES)
                                        .executes(ctx -> {
                                            runPinsList(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        }))))
                .then(Commands.literal("override").executes(ctx -> {
                    runOverrideList(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("classtree")
                        .executes(ctx -> {
                            String msg = com.bluesky.groovier.refer.ReferClasstree.dump();
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                            return 1;
                        })
                        .then(Commands.argument("filter", StringArgumentType.string())
                                .executes(ctx -> {
                                    String msg = com.bluesky.groovier.refer.ReferClasstree
                                            .dump(StringArgumentType.getString(ctx, "filter"));
                                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                    return 1;
                                })))
                .then(Commands.literal("refer").executes(ctx -> {
                    // 后台重跑 refer 导出(服务器启动时已自动跑过一次;懒加载类此时已就绪)
                    com.bluesky.groovier.refer.ReferExporter.runAsync();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Refer export started in background (groovy_scripts/refer/, see groovier.log)."), false);
                    return 1;
                }))
                .then(Commands.literal("help").executes(ctx -> {
                    Groovier.getSandbox().showHelp(ctx.getSource());
                    exportGuide(ctx.getSource());
                    return 1;
                }));
    }

    /** 将内置使用指南导出到 local/groovier-guide.md(GAMEDIR 基准,原子写)。 */
    private static void exportGuide(CommandSourceStack source) {
        try (var in = GroovierCommand.class.getResourceAsStream("/groovier-guide.md")) {
            if (in == null) {
                source.sendFailure(Component.literal("Bundled guide resource missing (groovier-guide.md)."));
                return;
            }
            java.nio.file.Path out = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("local").resolve("groovier-guide.md");
            com.bluesky.groovier.util.AtomicFiles.write(out, in.readAllBytes());
            source.sendSuccess(() -> Component.literal(
                    "Full guide exported to local/groovier-guide.md"), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to export guide: " + e));
        }
    }

    private static int runListScripts(CommandContext<CommandSourceStack> ctx) {
        Groovier.getSandbox().listScripts(ctx.getSource());
        return 1;
    }

    private static void runMixinReport(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        if (!java.nio.file.Files.exists(MixinReportReader.reportPath())) {
            source.sendSuccess(() -> Component.literal(
                    "No mixin invalidation report (blacklist empty or no blacklisted class loaded)."), false);
            return;
        }
        try {
            MixinReportReader.Report report = MixinReportReader.read();
            source.sendSuccess(() -> Component.literal("=== Mixin invalidation report ==="), false);
            if (report.blacklistRules().isEmpty()) {
                source.sendSuccess(() -> Component.literal("blacklist: <empty>"), false);
            } else {
                for (String rule : report.blacklistRules()) {
                    source.sendSuccess(() -> Component.literal("blacklist: " + rule), false);
                }
            }
            if (report.targets().isEmpty()) {
                source.sendSuccess(() -> Component.literal("no blacklisted class loaded yet"), false);
            }
            for (MixinReportReader.TargetReport t : report.targets()) {
                if ("channel_failed".equals(t.status())) {
                    source.sendSuccess(() -> Component.literal(t.target() + " -> CHANNEL FAILED (mixins kept)"), false);
                    continue;
                }
                source.sendSuccess(() -> Component.literal(
                        t.target() + " -> " + t.removedMixins().size() + " mixin(s) removed"
                                + (t.keptMixins().isEmpty() ? "" : ", " + t.keptMixins().size() + " kept")), false);
                for (String mixin : t.removedMixins()) {
                    source.sendSuccess(() -> Component.literal("  - " + mixin), false);
                }
                for (String mixin : t.keptMixins()) {
                    source.sendSuccess(() -> Component.literal("  + kept: " + mixin), false);
                }
            }
            source.sendSuccess(() -> Component.literal(
                    "details: local/mixin_invalidated/ (report.json + pre/*.class)"), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to read report: " + e));
        }
    }

    /** /gvr surgery [remove <name>]:手术包清单(状态来自 report.json surgeries 块) */
    private static void runSurgeryList(CommandSourceStack source, String removeName) {
        if (removeName != null) {
            boolean removed = com.bluesky.groovier.api.SurgeryApi.INSTANCE.remove(removeName);
            source.sendSuccess(() -> Component.literal(removed
                    ? "Surgery pack '" + removeName + "' removed (takes effect next launch)."
                    : "No surgery pack named '" + removeName + "'."), false);
        }
        var packs = com.bluesky.groovier.api.SurgeryApi.INSTANCE.list();
        if (packs.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No surgery packs installed (scripts: Surgery.submit(...); artifacts: local/surgeries/)."), false);
            return;
        }
        java.util.Map<String, String> states = java.util.Map.of();
        try {
            if (java.nio.file.Files.exists(MixinReportReader.reportPath())) {
                states = MixinReportReader.read().surgeries();
            }
        } catch (Exception ignored) {
            // 报告缺失/损坏时只显示 manifest 信息
        }
        source.sendSuccess(() -> Component.literal("=== Surgery packs ==="), false);
        for (var entry : packs.entrySet()) {
            var info = entry.getValue();
            String status = states.getOrDefault(entry.getKey(), "not applied this launch");
            source.sendSuccess(() -> Component.literal(entry.getKey() + " -> " + info.getOrDefault("target", "?")
                    + " (" + info.getOrDefault("mode", "?") + ")"
                    + (info.getOrDefault("drop", "").isBlank() ? "" : " drop=[" + info.get("drop") + "]")
                    + (Boolean.parseBoolean(info.getOrDefault("hasPatch", "false")) ? "" : " [NO PATCH FILE]")
                    + " | " + status), false);
        }
        source.sendSuccess(() -> Component.literal("artifacts: local/surgeries/ ; takes effect on next launch"), false);
    }

    /** /gvr override:整类覆盖绑定清单(配置规则 × bind 报告 × report.json 注入状态) */
    private static void runOverrideList(CommandSourceStack source) {
        // OverrideManager 以 GAMEDIR 为基准读写,读端须一致(不可用进程 cwd)
        java.nio.file.Path configFile = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("config").resolve("groovier-override.txt");
        if (!java.nio.file.Files.exists(configFile)) {
            source.sendSuccess(() -> Component.literal(
                    "No override config (config/groovier-override.txt; syntax = groovier-refer.txt). "
                            + "Override sources: groovy_scripts/override/*.groovy."), false);
            return;
        }
        // bind 报告:fqn -> [status, detail]
        java.util.Map<String, String[]> binds = new java.util.LinkedHashMap<>();
        java.nio.file.Path bindPath = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("local").resolve("override").resolve("bind.txt");
        if (java.nio.file.Files.exists(bindPath)) {
            try {
                for (String raw : java.nio.file.Files.readAllLines(bindPath)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(" \\| ", 3);
                    if (parts.length >= 2) {
                        binds.put(parts[0], new String[] {parts[1], parts.length > 2 ? parts[2] : ""});
                    }
                }
            } catch (Exception e) {
                source.sendFailure(Component.literal("Failed to read bind report: " + e));
                return;
            }
        }
        // 注入状态(report.json overrides 块):fqn -> status
        java.util.Map<String, String> injected = new java.util.LinkedHashMap<>();
        java.nio.file.Path report = MixinReportReader.reportPath();
        if (java.nio.file.Files.exists(report)) {
            try {
                boolean in = false;
                for (String raw : java.nio.file.Files.readAllLines(report)) {
                    String line = raw.trim();
                    if (line.startsWith("\"overrides\": {")) {
                        in = true;
                        continue;
                    }
                    if (in) {
                        if (line.startsWith("}")) {
                            break;
                        }
                        int keyEnd = line.indexOf("\":");
                        if (line.startsWith("\"") && keyEnd > 0) {
                            String fqn = line.substring(1, keyEnd);
                            String status = extractJsonValue(line);
                            injected.put(fqn, status);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 报告缺失/损坏时注入状态列显示 unknown
            }
        }
        source.sendSuccess(() -> Component.literal("=== Override bindings ==="), false);
        if (binds.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "no bindings (sources under groovy_scripts/override/ compiled on launch/reload)"), false);
        }
        for (var entry : binds.entrySet()) {
            String fqn = entry.getKey();
            String[] bind = entry.getValue();
            String inj = injected.getOrDefault(fqn, "-");
            source.sendSuccess(() -> Component.literal(fqn + " -> " + bind[0]
                    + " | injected: " + inj
                    + (bind[1].isEmpty() ? "" : " | " + bind[1])), false);
        }
        source.sendSuccess(() -> Component.literal(
                "bind report: local/override/bind.txt ; replacement at class load (coprocessor, post-mixin)"), false);
    }

    /** report.json overrides 条目("fqn": {"status": "..."})的状态值提取(转义感知) */
    private static String extractJsonValue(String line) {
        String key = "\"status\": \"";
        int start = line.indexOf(key);
        if (start < 0) {
            return "?";
        }
        return MixinReportReader.extractString(line, start + key.length());
    }

    /** /gvr pins [remove <name>]:钉子包清单(manifest + 注入状态 + 运行期回调数) */
    private static void runPinsList(CommandSourceStack source, String removeName) {
        var api = com.bluesky.groovier.api.PinsApi.INSTANCE;
        if (removeName != null) {
            boolean removed = api.remove(removeName);
            source.sendSuccess(() -> Component.literal(removed
                    ? "Pin pack '" + removeName + "' removed (takes effect next launch)."
                    : "No pin pack named '" + removeName + "'."), false);
        }
        var pins = api.list();
        if (pins.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No pin packs installed (scripts: Pins.declare(...); artifacts: local/pins/)."), false);
            return;
        }
        source.sendSuccess(() -> Component.literal("=== Pin packs ==="), false);
        for (var entry : pins.entrySet()) {
            var info = entry.getValue();
            source.sendSuccess(() -> Component.literal(entry.getKey() + " -> "
                    + info.getOrDefault("target", "?") + "." + info.getOrDefault("method", "?")
                    + (info.getOrDefault("descriptor", "").isBlank() ? "" : info.get("descriptor"))
                    + " | " + info.getOrDefault("state", "?")
                    + (info.containsKey("callbacks") ? " | " + info.get("callbacks") : "")), false);
        }
        source.sendSuccess(() -> Component.literal(
                "artifacts: local/pins/ ; injection on next launch, overrides via Pins.override(key){...} at runtime"), false);
    }
}
