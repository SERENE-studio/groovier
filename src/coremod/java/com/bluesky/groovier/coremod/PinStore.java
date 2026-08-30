package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 钉子包装载器(启动期,coremod 侧;零脚本依赖)。SPEC 6.3 方法钉子,原身通路。
 *
 * 钉子包 = 运行期脚本(GAME 层 Groovier Pins API)产出的目录:
 *   local/pins/<name>/pin.txt   零依赖行式 manifest
 *
 * manifest 字段:
 *   target=<点分目标类名>       (必填)
 *   method=<方法名>             (必填;命中该类全部同名方法/重载)
 *   descriptor=<方法描述符>     (可选;(II)Ljava/lang/String; 形式,精确到单个重载)
 *   enabled=true|false          (默认 true)
 *
 * 生效流程(PinningTransformer):类首次加载(ITransformer 阶段,mixin 前)→
 * 命中方法重命名包装,方法入口生成 GroovierHooks 查询点,未命中透明放行。
 *
 * 注意:本类仅 SERVICE 层,GAME 层经文件产物交互(coremod 类对 GAME 层不可见)。
 */
final class PinStore {

    record PinPlan(String name, String target, String method, String descriptor, boolean enabled) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.PinStore");

    /** name -> plan */
    private static volatile Map<String, PinPlan> plans = Map.of();
    /** target -> 该类的全部钉子(同名方法多钉冲突时首个生效,报告可见) */
    private static volatile Map<String, List<PinPlan>> byTarget = Map.of();

    private PinStore() {}

    static void load(IEnvironment environment) {
        // SERVICE 层 onLoad 时 FML 尚未注入 GAMEDIR;回退 user.dir(dev 运行的工作目录即 run/)
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseGet(() -> Path.of(System.getProperty("user.dir")));
        Path root = gameDir.resolve("local").resolve("pins");
        Map<String, PinPlan> loaded = new LinkedHashMap<>();
        Map<String, List<PinPlan>> index = new HashMap<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
                for (Path dir : dirs) {
                    PinPlan plan = loadOne(dir);
                    if (plan != null) {
                        loaded.put(plan.name(), plan);
                        index.computeIfAbsent(plan.target(), k -> new ArrayList<>()).add(plan);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to list pin packs at {}", root, e);
            }
        }
        plans = Map.copyOf(loaded);
        byTarget = Map.copyOf(index);
        if (!loaded.isEmpty()) {
            LOGGER.info("Groovier pin packs loaded: {} pack(s), {} target(s)", loaded.size(), index.size());
        }
    }

    static List<PinPlan> pinsFor(String className) {
        return byTarget.getOrDefault(className, List.of());
    }

    static Set<String> resolvedTargets() {
        return byTarget.keySet();
    }

    private static PinPlan loadOne(Path dir) {
        Path manifest = dir.resolve("pin.txt");
        if (!Files.isRegularFile(manifest)) {
            LOGGER.warn("Pin pack missing manifest, ignored: {}", dir);
            return null;
        }
        String name = dir.getFileName().toString();
        String target = null;
        String method = null;
        String descriptor = null;
        boolean enabled = true;
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    LOGGER.warn("Pin pack {} invalid manifest line ignored: {}", name, trimmed);
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                switch (key) {
                    case "target" -> target = value;
                    case "method" -> method = value;
                    case "descriptor" -> descriptor = value.isEmpty() ? null : value;
                    case "enabled" -> enabled = !value.equals("false");
                    default -> LOGGER.warn("Pin pack {} unknown manifest key ignored: {}", name, key);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Pin pack manifest read failed: {}", manifest, e);
            return null;
        }
        if (target == null || target.isBlank() || method == null || method.isBlank()) {
            LOGGER.warn("Pin pack {} invalid manifest (target/method), ignored", name);
            return null;
        }
        if (!enabled) {
            return null;
        }
        return new PinPlan(name, target, method, descriptor, true);
    }
}
