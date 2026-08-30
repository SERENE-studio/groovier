package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 手术包装载器(启动期,coremod 侧;零脚本依赖)。
 *
 * 手术包 = 运行期脚本(GAME 层 Groovier Surgery API)产出的目录:
 *   local/surgeries/<name>/surgery.txt   零依赖行式 manifest
 *   local/surgeries/<name>/patch.class   补丁后的目标类字节码(脚本基于 pre 原身改写)
 *
 * manifest 字段:
 *   target=<点分目标类名>                  (必填)
 *   mode=patch_with_mixins | exclusive     (默认 patch_with_mixins:仅摘 drop 清单,
 *                                           其余 mixin 照常叠加到补丁输出 = "继续 mixin";
 *                                           exclusive:全摘,类由补丁全权维护)
 *   preSha256=<64hex>                      pre 原身 SHA-256,失配 = 手术包停用(锚定)
 *   drop=<逗号分隔 mixin 规格>              可空;精确类名或 prefix.*
 *
 * 生效流程(MixinInvalidationTransformer):类首次加载 → pre 捕获 → 哈希锚定校验 →
 * 按 mode 摘除 → 返回补丁字节码(阶段 3 存活 mixin 继续应用)。
 *
 * 注意:本类仅 SERVICE 层,GAME 层经文件产物交互(coremod 类对 GAME 层不可见)。
 */
final class SurgeryStore {

    record SurgeryPlan(String name, String target, boolean exclusive, List<String> dropSpecs,
                       byte[] patchBytes, String preSha256) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.SurgeryStore");

    /** name -> plan;patch 字节启动期一次性读入,transform 时零 IO */
    private static volatile Map<String, SurgeryPlan> plans = Map.of();
    /** target -> plan 索引 */
    private static volatile Map<String, SurgeryPlan> byTarget = Map.of();

    private SurgeryStore() {}

    static void load(IEnvironment environment) {
        // GAMEDIR 定位统一走 CoremodFiles(IEnvironment 键 → FMLPaths 反射);
        // 不回退 user.dir:进程 cwd 与 GAMEDIR 不一致时会读到错误位置的手术包
        Path gameDir = CoremodFiles.gameDir(environment);
        if (gameDir == null) {
            LOGGER.error("Groovier GAMEDIR unavailable, surgery packs disabled for this launch");
            return;
        }
        Path root = gameDir.resolve("local").resolve("surgeries");
        Map<String, SurgeryPlan> loaded = new LinkedHashMap<>();
        Map<String, SurgeryPlan> index = new HashMap<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
                for (Path dir : dirs) {
                    SurgeryPlan plan = loadOne(dir);
                    if (plan != null) {
                        if (index.containsKey(plan.target())) {
                            LOGGER.warn("Surgery pack {} overrides {} for target {} (later name wins)",
                                    plan.name(), index.get(plan.target()).name(), plan.target());
                        }
                        loaded.put(plan.name(), plan);
                        index.put(plan.target(), plan);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to list surgery packs at {}", root, e);
            }
        }
        plans = Map.copyOf(loaded);
        byTarget = Map.copyOf(index);
        if (!loaded.isEmpty()) {
            LOGGER.info("Groovier surgery packs loaded: {} pack(s), {} target(s)", loaded.size(), index.size());
        }
    }

    static SurgeryPlan forTarget(String className) {
        return byTarget.get(className);
    }

    static Set<String> resolvedTargets() {
        return byTarget.keySet();
    }

    private static SurgeryPlan loadOne(Path dir) {
        Path manifest = dir.resolve("surgery.txt");
        if (!Files.isRegularFile(manifest)) {
            LOGGER.warn("Surgery pack missing manifest, ignored: {}", dir);
            return null;
        }
        String name = dir.getFileName().toString();
        String target = null;
        boolean exclusive = false;
        String preSha256 = null;
        List<String> dropSpecs = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    LOGGER.warn("Surgery pack {} invalid manifest line ignored: {}", name, trimmed);
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                switch (key) {
                    case "target" -> target = value;
                    // mode 拼写错误(exclusive 误写/大小写不符)曾静默降级为 patch_with_mixins,
                    // 语义反转;未识别的 mode 直接告警并拒绝加载该手术包
                    case "mode" -> {
                        if (value.equals("exclusive")) {
                            exclusive = true;
                        } else if (!value.equals("patch_with_mixins")) {
                            LOGGER.warn("Surgery pack {} unknown mode '{}' (expected exclusive|patch_with_mixins), pack rejected", name, value);
                            return null;
                        }
                    }
                    case "preSha256" -> preSha256 = value;
                    case "drop" -> {
                        for (String spec : value.split(",")) {
                            if (!spec.isBlank()) {
                                dropSpecs.add(spec.trim());
                            }
                        }
                    }
                    default -> LOGGER.warn("Surgery pack {} unknown manifest key ignored: {}", name, key);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Surgery pack manifest read failed: {}", manifest, e);
            return null;
        }
        if (target == null || target.isBlank() || preSha256 == null || preSha256.length() != 64) {
            LOGGER.warn("Surgery pack {} invalid manifest (target/preSha256), ignored", name);
            return null;
        }
        Path patchFile = dir.resolve("patch.class");
        byte[] patchBytes;
        try {
            patchBytes = Files.readAllBytes(patchFile);
        } catch (IOException e) {
            LOGGER.error("Surgery pack {} patch read failed, ignored", name, e);
            return null;
        }
        return new SurgeryPlan(name, target, exclusive, List.copyOf(dropSpecs), patchBytes, preSha256);
    }

    /** 共享 SHA-256 hex(GAME 层 submit 与 coremod 锚定校验用同算法) */
    static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 供 GAME 层文档提示的根目录名(实际路径解析由 GAME 层自行拼接) */
    static Set<String> loadedPackNames() {
        return new LinkedHashSet<>(plans.keySet());
    }
}
