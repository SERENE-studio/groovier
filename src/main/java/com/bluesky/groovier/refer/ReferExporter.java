package com.bluesky.groovier.refer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 6.5 反编译导出(SPEC §6.5):coremod 残局捕获落盘的 .class(local/refer/classes/)
 * 后台经 Vineflower 反编译为 groovy_scripts/refer/*.java,作为整类覆盖(6.4)的
 * 复刻参考模板——mixin 行为以复刻形式保留。
 *
 * 数据通路:coremod ReferStore(coprocessor 回调,mixin 后、define 前)只落字节;
 * 本类在 GAME 层(完整游戏类路径)做反编译。产物为 .java,脚本扫描只认 .groovy,
 * refer/ 天然不会被误执行。
 *
 * 混淆类:启发式检测(外部类简单名长度 <= 2)跳过 + 告警,不产出参考文件;
 * 字节码保留在 classes/ 下可供工具 diff。启发式只兜显式混淆名,可读名混淆类
 * 无法检测,属已知限制。
 *
 * 触发:服务器启动后台自动一次;/gvr refer 可随时重跑(幂等,覆盖旧产物)。
 */
public final class ReferExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferExporter.class);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ReferExporter() {}

    /** coremod 残局捕获目录(local/refer/classes/) */
    public static Path classesDir() {
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("refer").resolve("classes");
    }

    /** 参考产物目录(groovy_scripts/refer/) */
    public static Path referRoot() {
        return FMLPaths.GAMEDIR.get().resolve("groovy_scripts").resolve("refer");
    }

    /** 后台导出(守护线程,防重入;已在跑则跳过) */
    public static void runAsync() {
        Thread thread = new Thread(ReferExporter::runSafely, "Groovier-Refer-Export");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runSafely() {
        if (!RUNNING.compareAndSet(false, true)) {
            LOGGER.info("Refer export already running, skipped");
            return;
        }
        try {
            export();
        } catch (Throwable t) {
            LOGGER.error("Refer export failed", t);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void export() throws Exception {
        Path classes = classesDir();
        if (!Files.isDirectory(classes)) {
            LOGGER.info("Refer export skipped: no captured classes "
                    + "(create config/groovier-refer.txt with target classes and restart)");
            return;
        }
        Path work = classes.getParent();
        Path staging = work.resolve("staging");
        Path out = work.resolve("out");
        deleteRecursively(staging);
        deleteRecursively(out);

        // 1. staging:混淆启发式过滤(classes 原样保留,过滤只在副本上做)
        List<Path> classFiles;
        try (Stream<Path> stream = Files.walk(classes)) {
            classFiles = stream.filter(p -> p.getFileName().toString().endsWith(".class")).toList();
        }
        int kept = 0;
        int skipped = 0;
        for (Path p : classFiles) {
            String rel = classes.relativize(p).toString();
            String dotted = rel.replace('\\', '.').replace('/', '.').replaceFirst("\\.class$", "");
            if (isLikelyObfuscated(dotted)) {
                skipped++;
                LOGGER.warn("Refer export: skipping likely obfuscated class {}", dotted);
                continue;
            }
            Path dest = staging.resolve(classes.relativize(p));
            Files.createDirectories(dest.getParent());
            Files.copy(p, dest);
            kept++;
        }
        if (kept == 0) {
            LOGGER.info("Refer export: nothing to decompile ({} skipped)", skipped);
            return;
        }

        // 2. Vineflower 目录模式反编译(staging → out,保留包结构)
        Decompiler decompiler = Decompiler.builder()
                .inputs(staging.toFile())
                .output(new DirectoryResultSaver(out.toFile()))
                .build();
        decompiler.decompile();

        // 3. 清掉 refer 根下旧 .java(产物快照化,避免陈旧参考),落新产物
        Path root = referRoot();
        Files.createDirectories(root);
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".java")).toList()) {
                Files.deleteIfExists(p);
            }
        }
        int written = 0;
        try (Stream<Path> stream = Files.walk(out)) {
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".java")).toList()) {
                Path dest = root.resolve(out.relativize(p).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
        }
        deleteRecursively(staging);
        deleteRecursively(out);
        LOGGER.info("Refer export done: {} file(s) -> groovy_scripts/refer/, {} skipped (obfuscation heuristic)",
                written, skipped);
    }

    /** 混淆启发式:外部类简单名长度 <= 2 视为混淆(内部类跟随外部类判断) */
    public static boolean isLikelyObfuscated(String className) {
        String simple = className;
        int slash = simple.lastIndexOf('/');
        if (slash >= 0) {
            simple = simple.substring(slash + 1);
        }
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        int dollar = simple.indexOf('$');
        if (dollar >= 0) {
            simple = simple.substring(0, dollar);
        }
        return simple.length() <= 2;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
