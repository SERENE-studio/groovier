package com.bluesky.groovier.override;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bluesky.groovier.Groovier;
import com.bluesky.groovier.engine.GroovierClassLoader;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 6.4 整类覆盖绑定(SPEC §6.4/§6.5 第 4 件套)。
 *
 * <p>通路(与项目"文件为跨层通道"惯例同构,避免 SERVICE/GAME 层类空间互通):
 * <ol>
 *   <li>扫描 {@code groovy_scripts/override/} 下的 .groovy(纯类文件,基于 refer 模板复刻,
 *       package/类名与目标一致);</li>
 *   <li>影子编译:GroovierClassLoader 的 parent 为影子加载器,目标类解析优先取
 *       {@code local/refer/classes/} 残局字节、其次游戏 jar 原始字节(resource 读取,
 *       不经 define)——编译期绝不经游戏类加载器触发目标类加载(否则 coprocessor
 *       回调在绑定落盘前发生,替换永久错过);</li>
 *   <li>ApiDiff 签名契约校验:基准 = refer 残局字节(首选,含 mixin 最终形态)或
 *       游戏 jar 原始字节(降级,bind 行标注);破坏性缺失 → 阻止(不落盘);</li>
 *   <li>通过者落盘 {@code local/override/classes/<fqn>.class}。核心侧 OverrideStore
 *       在目标类 coprocessor 回调(mixin 后、define 前)读取并整类替换。</li>
 * </ol>
 *
 * <p>触发:主模组构造期(最早 GAME 层入口)一次 + {@code /groovier reload} 后台重跑
 * (重编译重落盘;已加载类无法再替换,属机制边界)。快照化:落盘前清除陈旧 .class。
 */
public final class OverrideManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.override.OverrideManager");

    private OverrideManager() {}

    /** 覆盖源目录(groovy_scripts/override/) */
    public static Path scriptsRoot() {
        return FMLPaths.GAMEDIR.get().resolve("groovy_scripts").resolve("override");
    }

    /** 替换字节落盘目录(local/override/classes/,核心侧读取) */
    public static Path outClassesDir() {
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("override").resolve("classes");
    }

    /** refer 残局字节目录(coremod ReferStore 产物,编译期影子解析 + 契约基准) */
    public static Path referClassesDir() {
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("refer").resolve("classes");
    }

    /** 绑定报告(local/override/bind.txt,每次 rebind 全量重写) */
    public static Path bindReport() {
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("override").resolve("bind.txt");
    }

    /** 编译 override 源并落盘(启动期与 reload 均调用;幂等快照化,失败仅告警不断启动)。 */
    public static synchronized void rebind() {
        try {
            doRebind();
        } catch (Throwable t) {
            LOGGER.error("Override bind failed (override channel disabled this session)", t);
        }
    }

    private static void doRebind() throws Exception {
        Config config = parseConfig();
        List<Path> sources = new ArrayList<>();
        Path root = scriptsRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".groovy")).forEach(sources::add);
            }
        }
        sources.sort(Comparator.comparing(Path::toString));
        if (sources.isEmpty()) {
            clearStaleClasses(Set.of());
            writeBindReport(List.<String[]>of(new String[] {"-", "skipped",
                    "no override sources under " + root}));
            LOGGER.info("Override bind: no override sources, nothing bound");
            return;
        }
        LOGGER.info("Override bind: {} source file(s), {} config rule(s)", sources.size(), config.ruleCount());

        // 影子编译:每文件独立捕获,编译错误可归属
        Map<String, byte[]> compiled = new HashMap<>();
        Map<String, String> fileOf = new HashMap<>();
        List<String[]> binds = new ArrayList<>(); // {fqn, status, detail}
        ReferShadowClassLoader shadow = new ReferShadowClassLoader(Groovier.class.getClassLoader());
        GroovierClassLoader loader = new GroovierClassLoader(shadow, Groovier.getSandbox().compilerConfig());
        for (Path source : sources) {
            String rel = root.relativize(source).toString();
            List<GroovierClassLoader.CompiledClass> captured = new ArrayList<>();
            loader.setCompiledClassConsumer(captured::add);
            try {
                loader.parseClass(source.toFile());
                for (GroovierClassLoader.CompiledClass cc : captured) {
                    String prev = fileOf.get(cc.className());
                    if (prev == null) {
                        compiled.put(cc.className(), cc.code());
                        fileOf.put(cc.className(), rel);
                    } else if (!prev.equals(rel)) {
                        binds.add(new String[] {cc.className(), "duplicate",
                                "src=" + rel + "; ignored, already defined by " + prev});
                        LOGGER.warn("Override bind: duplicate class {} from {} ignored (already defined by {})",
                                cc.className(), rel, prev);
                    }
                }
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage()).lines().findFirst().orElse(t.toString());
                binds.add(new String[] {"-", "compile-error", "src=" + rel + "; " + msg});
                LOGGER.error("Override bind: compile failed for {}", rel, t);
            } finally {
                loader.setCompiledClassConsumer(null);
            }
        }

        // 逐产物判定:非目标(辅助/内部类)跳过;目标走契约校验;通过落盘
        Set<String> registered = new LinkedHashSet<>();
        List<String> fqns = new ArrayList<>(compiled.keySet());
        fqns.sort(Comparator.naturalOrder());
        for (String fqn : fqns) {
            String src = fileOf.get(fqn);
            if (!config.isTarget(fqn)) {
                binds.add(new String[] {fqn, "skipped", "src=" + src + "; not an override target (helper/inner class)"});
                continue;
            }
            Baseline baseline = baselineFor(fqn, shadow);
            if (baseline == null) {
                binds.add(new String[] {fqn, "blocked", "src=" + src
                        + "; no baseline (add target to config/groovier-refer.txt and restart, or load it once then /gvr refer)"});
                LOGGER.warn("Override bind: {} blocked, no baseline bytes available", fqn);
                continue;
            }
            String baselineKind = baseline.kind();
            ApiDiff.Result diff = ApiDiff.diff(baseline.bytes(), compiled.get(fqn));
            if (diff.breaking()) {
                binds.add(new String[] {fqn, "blocked", "src=" + src + "; baseline=" + baselineKind
                        + "; missing " + diff.missing().size() + " external member(s): "
                        + String.join("; ", diff.missing())});
                LOGGER.warn("Override bind: {} blocked by API diff ({} missing external member(s), baseline={})",
                        fqn, diff.missing().size(), baselineKind);
                diff.missing().forEach(m -> LOGGER.warn("Override bind:   missing {}", m));
                continue;
            }
            try {
                Path out = outClassesDir().resolve(fqn.replace('.', '/') + ".class");
                Files.createDirectories(out.getParent());
                atomicWrite(out, compiled.get(fqn));
                registered.add(fqn);
                String detail = "src=" + src + "; baseline=" + baselineKind
                        + (diff.added().isEmpty() ? "" : "; " + diff.added().size() + " added")
                        + (diff.missingPrivate().isEmpty() ? ""
                                : "; WARN " + diff.missingPrivate().size() + " private member(s) dropped (nestmate risk)");
                binds.add(new String[] {fqn, "registered", detail});
                LOGGER.info("Override bind: {} registered (baseline={}, {} added, {} private dropped)",
                        fqn, baselineKind, diff.added().size(), diff.missingPrivate().size());
            } catch (IOException e) {
                binds.add(new String[] {fqn, "error", "src=" + src + "; write failed: " + e});
                LOGGER.error("Override bind: write failed for {}", fqn, e);
            }
        }

        clearStaleClasses(registered);
        writeBindReport(binds);
        LOGGER.info("Override bind done: {} registered, {} blocked/failed", registered.size(),
                binds.size() - registered.size());
    }

    /** 原子写:先写同目录 temp 再 ATOMIC_MOVE,防 reload 期间懒加载命中读空/半截 class → ClassFormatError。 */
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 快照化:本轮未注册的陈旧 .class 全部清除(含 override 源已删除的场景)。 */
    private static void clearStaleClasses(Set<String> registered) throws IOException {
        Path out = outClassesDir();
        if (!Files.isDirectory(out)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(x -> x.getFileName().toString().endsWith(".class")).toList()) {
                String fqn = out.relativize(p).toString().replace('\\', '.').replace('/', '.')
                        .replaceFirst("\\.class$", "");
                if (!registered.contains(fqn)) {
                    Files.deleteIfExists(p);
                    LOGGER.info("Override bind: cleared stale binding {}", fqn);
                }
            }
        }
    }

    private static void writeBindReport(List<String[]> binds) {
        StringBuilder sb = new StringBuilder("# groovier override bind report (regenerated on launch and reload)\n");
        for (String[] b : binds) {
            sb.append(b[0]).append(" | ").append(b[1]).append(" | ").append(b[2]).append('\n');
        }
        try {
            Files.createDirectories(bindReport().getParent());
            Files.writeString(bindReport(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Override bind: failed to write bind report", e);
        }
    }

    /** 实际取材来源(refer 损坏降级 jar 时报告如实标 original-jar,不按文件存在性推断)。 */
    private record Baseline(byte[] bytes, String kind) {}

    /** 契约基准:refer 残局字节(首选,mixin 最终形态)→ 游戏 jar 原始字节(resource 读取,不触发加载)。 */
    private static Baseline baselineFor(String fqn, ReferShadowClassLoader shadow) {
        Path refer = referClassesDir().resolve(fqn.replace('.', '/') + ".class");
        if (Files.isRegularFile(refer)) {
            try {
                return new Baseline(Files.readAllBytes(refer), "residual");
            } catch (IOException e) {
                LOGGER.error("Override bind: refer baseline read failed for {}, falling back to original jar", fqn, e);
            }
        }
        try (InputStream in = shadow.getResourceAsStream(fqn.replace('.', '/') + ".class")) {
            if (in != null) {
                return new Baseline(in.readAllBytes(), "original-jar");
            }
        } catch (IOException e) {
            LOGGER.debug("Override bind: resource baseline unavailable for {}", fqn);
        }
        return null;
    }

    /** 配置(config/groovier-override.txt):精确类名 + prefix.*,语法与 groovier-refer.txt 同构。 */
    private record Config(Set<String> exact, Set<String> prefixes) {
        int ruleCount() {
            return exact.size() + prefixes.size();
        }

        boolean isTarget(String fqn) {
            if (exact.contains(fqn)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (fqn.startsWith(prefix + ".")) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Config parseConfig() throws IOException {
        Path configFile = FMLPaths.GAMEDIR.get().resolve("config").resolve("groovier-override.txt");
        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        if (Files.isRegularFile(configFile)) {
            for (String raw : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.endsWith(".*")) {
                    prefixes.add(line.substring(0, line.length() - 2));
                } else {
                    exact.add(line);
                }
            }
        }
        return new Config(Set.copyOf(exact), Set.copyOf(prefixes));
    }

    /**
     * 影子加载器:编译期类解析专用,目标是"拿到类定义而不经游戏类加载器加载"。
     * 优先级:refer 残局字节 → 父加载器 resource 原始字节(均就地 define,不触发
     * TransformingClassLoader 的类加载 → coprocessor 回调不会在绑定前发生)→
     * 常规 parent-first 兜底。仅服务编译期,不用于运行时。
     */
    private static final class ReferShadowClassLoader extends ClassLoader {

        private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

        ReferShadowClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> c = findLocally(name);
            if (c != null) {
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }

        private Class<?> findLocally(String name) {
            // JVM 禁止 define java.* 包类,且 JDK 类经父加载器加载不触达 TransformingClassLoader,无需影子
            if (name.startsWith("java.")) {
                return null;
            }
            Class<?> cached = cache.get(name);
            if (cached != null) {
                return cached;
            }
            String path = name.replace('.', '/') + ".class";
            byte[] bytes = null;
            Path refer = referClassesDir().resolve(path);
            if (Files.isRegularFile(refer)) {
                try {
                    bytes = Files.readAllBytes(refer);
                } catch (IOException e) {
                    LOGGER.debug("Shadow loader: refer bytes unreadable for {}", name);
                }
            }
            if (bytes == null) {
                try (InputStream in = getParent().getResourceAsStream(path)) {
                    if (in != null) {
                        bytes = in.readAllBytes();
                    }
                } catch (IOException e) {
                    LOGGER.debug("Shadow loader: resource bytes unavailable for {}", name);
                }
            }
            if (bytes == null) {
                return null;
            }
            Class<?> defined = defineClass(name, bytes, 0, bytes.length);
            cache.put(name, defined);
            return defined;
        }
    }
}
