package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 作废记录与产物落盘:
 *   local/mixin_invalidated/report.json          总报告(规则/目标/摘除明细)
 *   local/mixin_invalidated/pre/<类名>.class      pre-mixin 原身字节码(6.5 反编译导出的输入)
 *
 * 报告以全量重写方式落盘(启动期低频,类加载数量级 = 黑名单目标数),经 CoremodFiles 原子写
 * (GAME 层读端不会看到半截文件)。
 * JSON 手写拼装:coremod 源集无 Gson 保证,全部字符串经 json() 做 JSON 标准转义
 * (name/status 可能携带用户提供的短语与异常文本,不能假设无特殊字符)。
 * keptMixins 以单行紧凑 JSON 写出(游戏侧行解析器按前缀识别,不破坏 target 键行判定)。
 */
final class InvalidationStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.InvalidationStore");
    private static final Object IO_LOCK = new Object();

    /** className(点分) -> 摘除记录;null 表示摘除通道失败;removed+kept 均空 = 该类本无 mixin */
    private static final Map<String, MixinRegistryProxy.InvalidatedMixins> INVALIDATIONS = new LinkedHashMap<>();
    /** name -> [target, status] 手术包状态(启动期一次性) */
    private static final Map<String, String[]> SURGERY_STATES = new LinkedHashMap<>();
    /** name -> [target, status] 钉子包状态(启动期一次性) */
    private static final Map<String, String[]> PIN_STATES = new LinkedHashMap<>();
    /** className -> 整类替换状态(6.4,启动期一次性;applied/no override bytecode/error) */
    private static final Map<String, String> OVERRIDES = new LinkedHashMap<>();
    private static Path outDir;

    private InvalidationStore() {}

    static void init(IEnvironment environment) {
        // GAMEDIR 定位统一走 CoremodFiles(IEnvironment 键 → FMLPaths 反射);
        // 不回退 user.dir:进程 cwd 与 GAMEDIR 不一致时写读错位,宁可禁用产物
        Path gameDir = CoremodFiles.gameDir(environment);
        if (gameDir == null) {
            LOGGER.error("Groovier GAMEDIR unavailable, mixin invalidation report disabled for this launch");
            return;
        }
        outDir = gameDir.resolve("local").resolve("mixin_invalidated");
    }

    static void preCapture(String className, byte[] bytes) {
        Path dir = outDir;
        if (dir == null) {
            return;
        }
        synchronized (IO_LOCK) {
            try {
                Path file = dir.resolve("pre").resolve(className.replace('.', '/') + ".class");
                // pre 产物被 GAME 层(Surgery.pre/submit)读取,原子写避免读到半截
                CoremodFiles.atomicWrite(file, bytes);
            } catch (IOException e) {
                LOGGER.error("Failed to write pre-mixin capture for {}", className, e);
            }
        }
    }

    static void logCaptureFailure(String className, RuntimeException e) {
        LOGGER.error("Pre-mixin capture failed for {}", className, e);
    }

    static void recordInvalidation(String className, MixinRegistryProxy.InvalidatedMixins result) {
        synchronized (IO_LOCK) {
            INVALIDATIONS.put(className, result);
            flush();
        }
        if (result == null) {
            LOGGER.error("Groovier could not invalidate mixins on {} (see earlier errors)", className);
        } else if (result.removed().isEmpty() && result.kept().isEmpty()) {
            LOGGER.info("Groovier invalidated mixins on {}: no mixin was targeting this class", className);
        } else if (result.kept().isEmpty()) {
            LOGGER.info("Groovier invalidated {} mixin(s) on {}", result.removed().size(), className);
        } else {
            LOGGER.info("Groovier surgically invalidated {} mixin(s) on {} ({} kept)",
                    result.removed().size(), className, result.kept().size());
        }
    }

    static void recordInvalidationFailure(String className, RuntimeException e) {
        LOGGER.error("Invalidation bookkeeping failed for {}", className, e);
    }

    /** 手术包状态(启动期应用结果;GAME 层 /gvr surgery 经 report.json 读取) */
    static void recordSurgery(String name, String target, String status) {
        synchronized (IO_LOCK) {
            SURGERY_STATES.put(name, new String[] {target, status});
            flush();
        }
    }

    /** 钉子包状态(启动期注入结果;GAME 层 /gvr pins 经 report.json 读取) */
    static void recordPin(String name, String target, String status) {
        synchronized (IO_LOCK) {
            PIN_STATES.put(name, new String[] {target, status});
            flush();
        }
    }

    /** 整类替换状态(启动期一次性;GAME 层 /gvr override 经 report.json 读取) */
    static void recordOverride(String className, String status) {
        synchronized (IO_LOCK) {
            OVERRIDES.put(className, status);
            flush();
        }
        if (status.startsWith("applied")) {
            LOGGER.info("Groovier override applied on {} ({})", className, status);
        }
    }

    private static void flush() {
        Path dir = outDir;
        if (dir == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(json(Instant.now().toString())).append("\",\n");
        sb.append("  \"blacklistRules\": ").append(jsonStringArray(MixinBlacklist.rawRules())).append(",\n");
        sb.append("  \"invalidations\": {\n");
        List<String> keys = new ArrayList<>(INVALIDATIONS.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String target = keys.get(i);
            MixinRegistryProxy.InvalidatedMixins result = INVALIDATIONS.get(target);
            sb.append("    \"").append(json(target)).append("\": ");
            if (result == null) {
                sb.append("{\"status\": \"channel_failed\"}");
            } else {
                sb.append("{\n      \"status\": \"invalidated\",\n      \"removedMixins\": {\n");
                List<String> mixins = new ArrayList<>(result.removed().keySet());
                for (int j = 0; j < mixins.size(); j++) {
                    String mixin = mixins.get(j);
                    sb.append("        \"").append(json(mixin)).append("\": \"")
                            .append(json(result.removed().get(mixin) == null ? "" : result.removed().get(mixin))).append("\"");
                    sb.append(j < mixins.size() - 1 ? ",\n" : "\n");
                }
                sb.append("      }");
                if (!result.kept().isEmpty()) {
                    // 单行紧凑输出,游戏侧行解析器按前缀识别,避免被误判为 target 键行
                    sb.append(",\n      \"keptMixins\": {");
                    List<String> kept = new ArrayList<>(result.kept().keySet());
                    for (int j = 0; j < kept.size(); j++) {
                        if (j > 0) {
                            sb.append(", ");
                        }
                        sb.append('"').append(json(kept.get(j))).append("\": \"")
                                .append(json(result.kept().get(kept.get(j)))).append('"');
                    }
                    sb.append('}');
                }
                sb.append("\n    }");
            }
            sb.append(i < keys.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  },\n");
        sb.append("  \"surgeries\": {\n");
        List<String> names = new ArrayList<>(SURGERY_STATES.keySet());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String[] state = SURGERY_STATES.get(name);
            // 单行紧凑:读端按块首键定位 + 值内转义由 json() 保证合法
            sb.append("    \"").append(json(name)).append("\": {\"target\": \"").append(json(state[0]))
                    .append("\", \"status\": \"").append(json(state[1])).append("\"}");
            sb.append(i < names.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  },\n");
        sb.append("  \"pins\": {\n");
        List<String> pinNames = new ArrayList<>(PIN_STATES.keySet());
        for (int i = 0; i < pinNames.size(); i++) {
            String name = pinNames.get(i);
            String[] state = PIN_STATES.get(name);
            sb.append("    \"").append(json(name)).append("\": {\"target\": \"").append(json(state[0]))
                    .append("\", \"status\": \"").append(json(state[1])).append("\"}");
            sb.append(i < pinNames.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  },\n");
        // overrides 块置于最后:行解析器(PinsApi.reportPins/MixinReportReader)按块首键定位,
        // 新块放末尾对既有解析零影响
        sb.append("  \"overrides\": {\n");
        List<String> overrideKeys = new ArrayList<>(OVERRIDES.keySet());
        for (int i = 0; i < overrideKeys.size(); i++) {
            String className = overrideKeys.get(i);
            sb.append("    \"").append(json(className)).append("\": {\"status\": \"")
                    .append(json(OVERRIDES.get(className))).append("\"}");
            sb.append(i < overrideKeys.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        try {
            CoremodFiles.atomicWrite(dir.resolve("report.json"), sb.toString());
        } catch (IOException e) {
            LOGGER.error("Failed to write mixin invalidation report", e);
        }
    }

    private static String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append('"').append(json(values.get(i))).append('"');
            if (i < values.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.append(']').toString();
    }

    /** JSON 字符串标准转义:引号/反斜杠/控制字符(name、status 等可能含用户提供的任意短语) */
    private static String json(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
