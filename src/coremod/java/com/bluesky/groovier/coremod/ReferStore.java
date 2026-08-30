package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 6.5 反编译导出的残局字节码捕获(SPEC §6.5)。
 *
 * 文件: config/groovier-refer.txt(UTF-8)
 * 语法: 每行一条;# 注释;精确类名或包前缀 prefix.*(与 groovier-postwatch.txt 同构)。
 * 命中类: coprocessor 回调(mixin 应用后、define 前)捕获残局字节码,
 * 落盘 local/refer/classes/<包路径>/<类名>.class。
 * GAME 层 ReferExporter 后台反编译该目录 → groovy_scripts/refer/*.java。
 *
 * 与 PostWatch 的分工:PostWatch 服务冲突诊断(pre/post 成对);ReferStore 只要残局
 * (mixin 后最终形态 = 覆盖类的复刻基准)。仅取证不改写,恒返回 false。
 *
 * 混淆类不做拦截,原样落盘:启发式跳过放在 GAME 层导出侧(字节保留,便于工具 diff)。
 */
final class ReferStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.ReferStore");
    private static final String FILE_NAME = "groovier-refer.txt";

    /** 原始规则(诊断用) */
    private static final List<String> RAW_RULES = new ArrayList<>();
    /** 展开后的精确导出类名 */
    private static volatile Set<String> referTargets = Set.of();
    private static Path outDir;

    private ReferStore() {}

    static void load(IEnvironment environment) {
        // GAMEDIR 定位统一走 CoremodFiles(IEnvironment 键 → FMLPaths 反射);
        // 不回退 user.dir:进程 cwd 与 GAMEDIR 不一致时 refer 产物会写错位置
        Path gameDir = CoremodFiles.gameDir(environment);
        if (gameDir == null) {
            LOGGER.error("Groovier GAMEDIR unavailable, refer capture disabled for this launch");
            referTargets = Set.of();
            return;
        }
        outDir = gameDir.resolve("local").resolve("refer").resolve("classes");
        Path configFile = gameDir.resolve("config").resolve(FILE_NAME);
        List<String> rules = new ArrayList<>();
        if (Files.exists(configFile)) {
            try {
                for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    rules.add(trimmed);
                }
            } catch (IOException e) {
                LOGGER.error("Groovier refer read failed, refer capture disabled: {}", configFile, e);
            }
        } else {
            LOGGER.info("Groovier refer config not present ({}), refer capture disabled", configFile);
        }

        RAW_RULES.clear();
        RAW_RULES.addAll(rules);
        if (rules.isEmpty()) {
            referTargets = Set.of();
            return;
        }

        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        for (String rule : rules) {
            if (rule.contains(" ") || rule.equals("*") || rule.endsWith(".") || rule.contains("::")) {
                LOGGER.warn("Invalid refer rule ignored: {}", rule);
            } else if (rule.endsWith(".*")) {
                prefixes.add(rule.substring(0, rule.length() - 2));
            } else {
                exact.add(rule);
            }
        }
        Set<String> targets = new LinkedHashSet<>(exact);
        if (!prefixes.isEmpty()) {
            MixinBlacklist.expandPrefixTargets(gameDir.resolve("mods"), prefixes)
                    .values()
                    .forEach(targets::addAll);
        }
        referTargets = Set.copyOf(targets);
        LOGGER.info("Groovier refer loaded: {} rule(s) -> {} target class(es)", rules.size(), targets.size());
    }

    static List<String> rawRules() {
        return List.copyOf(RAW_RULES);
    }

    static Set<String> resolvedTargets() {
        return referTargets;
    }

    /** coprocessor 残局捕获(mixin 应用后、define 前);只取证不改写,fail-safe:失败不阻断 define */
    static boolean inspectPost(String className, ClassNode node) {
        if (!referTargets.contains(className)) {
            return false;
        }
        Path dir = outDir;
        if (dir == null) {
            return false;
        }
        try {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            Path file = dir.resolve(className.replace('.', '/') + ".class");
            // 产物被 GAME 层(ReferExporter)读取,原子写避免读到半截
            CoremodFiles.atomicWrite(file, writer.toByteArray());
        } catch (Throwable t) {
            LOGGER.error("Failed to write refer capture for {}", className, t);
        }
        return false;
    }
}
