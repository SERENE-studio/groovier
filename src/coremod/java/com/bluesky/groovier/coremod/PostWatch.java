package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 残局字节码观测(精准版,mixin coprocessor 通道)。
 *
 * 文件: config/groovier-postwatch.txt(UTF-8)
 * 语法: 每行一条;# 注释;精确类名或包前缀 prefix.*。
 * 命中类: pre 捕获(ITransformer 阶段) + post 捕获(mixin 应用后、define 前,经 coprocessor)
 * 落盘 local/mixin_invalidated/watch/{pre,post}/<类名>.class —— 同类 pre/post 成对,
 * 即冲突诊断的对照原料(残局 = 存活 mixin 叠加结果)。
 *
 * 机制(0.15.2 字节码确认):MixinCoprocessors extends ArrayList<MixinCoprocessor>,
 * postProcess(String, ClassNode) 对每个经 mixin 管线的类逐个回调,返回 true 表示原地修改。
 * 本类只取证不改写,恒返回 false。
 *
 * 注意:若观测类同时命中黑名单(摘除通道),其 mixin 已被移除,mixin 处理器可能跳过
 * postProcess 回调 → post 产物缺失属预期(该类最终形态 = pre 原身)。
 */
final class PostWatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.PostWatch");
    private static final String FILE_NAME = "groovier-postwatch.txt";

    /** 原始规则(诊断用) */
    private static final List<String> RAW_RULES = new ArrayList<>();
    /** 展开后的精确观测类名 */
    private static volatile Set<String> watchTargets = Set.of();
    private static Path outDir;

    private PostWatch() {}

    static void load(IEnvironment environment) {
        // GAMEDIR 定位统一走 CoremodFiles(IEnvironment 键 → FMLPaths 反射);
        // 不回退 user.dir:进程 cwd 与 GAMEDIR 不一致时观测产物会写错位置
        Path gameDir = CoremodFiles.gameDir(environment);
        if (gameDir == null) {
            LOGGER.error("Groovier GAMEDIR unavailable, post-mixin watching disabled for this launch");
            watchTargets = Set.of();
            return;
        }
        outDir = gameDir.resolve("local").resolve("mixin_invalidated").resolve("watch");
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
                LOGGER.error("Groovier postwatch read failed, watching disabled: {}", configFile, e);
            }
        } else {
            LOGGER.info("Groovier postwatch not present ({}), post-mixin watching disabled", configFile);
        }

        RAW_RULES.clear();
        RAW_RULES.addAll(rules);
        if (rules.isEmpty()) {
            watchTargets = Set.of();
            return;
        }

        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        for (String rule : rules) {
            if (rule.contains(" ") || rule.equals("*") || rule.endsWith(".") || rule.contains("::")) {
                LOGGER.warn("Invalid postwatch rule ignored: {}", rule);
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
        watchTargets = Set.copyOf(targets);
        LOGGER.info("Groovier postwatch loaded: {} rule(s) -> {} target class(es)", rules.size(), targets.size());
    }

    static List<String> rawRules() {
        return List.copyOf(RAW_RULES);
    }

    static Set<String> resolvedTargets() {
        return watchTargets;
    }

    static boolean isWatched(String className) {
        return watchTargets.contains(className);
    }

    /** pre-mixin 原身捕获(watch/pre/) */
    static void watchPre(String className, ClassNode node) {
        write(className, "pre", serialize(node));
    }

    /** post-mixin 残局捕获(coprocessor 回调,watch/post/);fail-safe:失败不阻断 define */
    static boolean inspectPost(String className, ClassNode node) {
        try {
            if (isWatched(className)) {
                write(className, "post", serialize(node));
            }
        } catch (Throwable t) {
            LOGGER.error("Groovier postwatch post-capture failed for {}", className, t);
        }
        return false; // 只取证,不改写
    }

    private static void write(String className, String phase, byte[] bytes) {
        Path dir = outDir;
        if (dir == null) {
            return;
        }
        try {
            Path file = dir.resolve(phase).resolve(className.replace('.', '/') + ".class");
            // 产物被 GAME 层(Surgery.pre/post)读取,原子写避免读到半截
            CoremodFiles.atomicWrite(file, bytes);
        } catch (IOException e) {
            LOGGER.error("Failed to write {} capture for {}", phase, className, e);
        }
    }

    private static byte[] serialize(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    static Optional<Path> outDir() {
        return Optional.ofNullable(outDir);
    }
}
