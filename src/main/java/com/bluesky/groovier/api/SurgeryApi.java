package com.bluesky.groovier.api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import groovy.lang.Closure;

import net.neoforged.fml.loading.FMLPaths;

import com.bluesky.groovier.util.AtomicFiles;

/**
 * 脚本手术 API(绑定名 {@code Surgery})—— mixin 手术体系(M2/M3)的运行期开发面。
 *
 * 定位:补丁在运行期开发(此时全类已加载,可反射/调用游戏类),在下次启动的类加载期同步执行
 * (coremod 手术包装载器,零脚本依赖)。两段式:开发(运行期) → 生效(冷启动)。
 *
 * 脚本用法:
 * <pre>
 *   Surgery.pre("com.example.Foo")          // 原身 ClassNode(需该类已配入 blacklist/postwatch 并启动过)
 *   Surgery.post("com.example.Foo")         // 残局 ClassNode(postwatch 产物)
 *   Surgery.report()                        // 摘除报告(report.json invalidations 概览)
 *   Surgery.submit(name: "fix-foo",
 *                  target: "com.example.Foo",
 *                  mode: "patch_with_mixins",   // 默认;或 "exclusive"(全摘,类由补丁全权维护)
 *                  drop: ["com.conflictmixin.BadMixin"]) { node -&gt;
 *       // node: ClassNode(基于原身);ASM 树形编辑方法体/字段/签名
 *   }
 *   Surgery.list()                          // 已安装手术包(名称/target/mode/状态)
 *   Surgery.remove("fix-foo")               // 删除手术包(下次启动不生效)
 * </pre>
 *
 * 产物(local/surgeries/&lt;name&gt;/):surgery.txt(行式 manifest,锚定 preSha256)+ patch.class。
 * 启动期:锚定校验通过 → 按 mode 摘除 mixin → 补丁字节码作为类加载输入(存活 mixin 继续叠加)。
 */
public final class SurgeryApi {

    public static final SurgeryApi INSTANCE = new SurgeryApi();

    private static final Path SURGERIES = FMLPaths.GAMEDIR.get().resolve("local").resolve("surgeries");
    private static final Path PRE_DIR = FMLPaths.GAMEDIR.get()
            .resolve("local").resolve("mixin_invalidated").resolve("pre");
    private static final Path POST_DIR = FMLPaths.GAMEDIR.get()
            .resolve("local").resolve("mixin_invalidated").resolve("watch").resolve("post");
    private static final Path REPORT = FMLPaths.GAMEDIR.get()
            .resolve("local").resolve("mixin_invalidated").resolve("report.json");

    private SurgeryApi() {}

    /** pre-mixin 原身 ClassNode;未捕获过返回 null(需先把类配入 blacklist/postwatch 并启动一次) */
    public Object pre(String targetClass) {
        return readNode(PRE_DIR.resolve(targetClass.replace('.', '/') + ".class"), "pre", targetClass);
    }

    /** post-mixin 残局 ClassNode;需类配入 groovier-postwatch.txt 且启动过 */
    public Object post(String targetClass) {
        return readNode(POST_DIR.resolve(targetClass.replace('.', '/') + ".class"), "post", targetClass);
    }

    /** 摘除报告概览(report.json invalidations:name -> status 摘要) */
    public Map<String, String> report() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(REPORT)) {
            GroovyLog.INSTANCE.warn("Surgery.report(): report.json not found ({}), launch once with a configured target first",
                    REPORT);
            return out;
        }
        try {
            boolean in = false;
            for (String raw : Files.readAllLines(REPORT)) {
                String line = raw.trim();
                if (line.startsWith("\"invalidations\": {")) {
                    in = true;
                    continue;
                }
                if (in) {
                    if (line.startsWith("}")) {
                        break;
                    }
                    // 仅 target 键行(写出端 4 空格缩进):"target": { 或单行 {"status": "channel_failed"}。
                    // 块内 status/removedMixins/mixin 键行缩进更深,必须跳过,否则会被误当 target
                    if (!raw.startsWith("    \"")) {
                        continue;
                    }
                    String target = line.startsWith("\"") ? line.substring(1, Math.max(1, line.indexOf("\":"))) : null;
                    if (target == null || target.isBlank()) {
                        continue;
                    }
                    String status = line.contains("channel_failed") ? "channel_failed" : "invalidated";
                    out.put(target, status);
                }
            }
        } catch (IOException e) {
            GroovyLog.INSTANCE.error("Surgery.report() read failed: {}", e);
        }
        return out;
    }

    /**
     * 提交手术包(下次启动生效)。
     *
     * @param spec  命名参数:name(默认 target 短名)、target(必填)、mode(patch_with_mixins|exclusive)、drop(List 或逗号串)
     * @param patch 闭包,参数为原身 ClassNode,就地修改
     * @return 摘要 Map(name/target/mode/drop/patchBytes/preSha256/location)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(Map<String, Object> spec, Closure<?> patch) {
        String target = str(spec.get("target"));
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Surgery.submit requires 'target'");
        }
        // 类名清洗:拒绝路径分隔符、'..'、'<init>' 等非法成分(直接报错,不静默转换)
        validateTarget(target);
        String name = str(spec.get("name"));
        if (name == null || name.isBlank()) {
            name = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
        }
        validatePackName(name);
        String mode = str(spec.get("mode"));
        boolean exclusive = "exclusive".equals(mode);
        List<String> drop = new ArrayList<>();
        Object dropSpec = spec.get("drop");
        if (dropSpec instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null && !o.toString().isBlank()) {
                    drop.add(o.toString().trim());
                }
            }
        } else if (dropSpec != null && !dropSpec.toString().isBlank()) {
            for (String s : dropSpec.toString().split(",")) {
                if (!s.isBlank()) {
                    drop.add(s.trim());
                }
            }
        }

        Path preFile = PRE_DIR.resolve(target.replace('.', '/') + ".class");
        if (!Files.isRegularFile(preFile)) {
            throw new IllegalStateException("No pre-mixin capture for " + target
                    + " — add it to groovier-mixin-blacklist.txt or groovier-postwatch.txt and launch once");
        }
        byte[] preBytes;
        try {
            preBytes = Files.readAllBytes(preFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read pre capture: " + preFile, e);
        }

        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(preBytes).accept(node, 0);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Pre capture is not parseable bytecode: " + preFile, e);
        }
        patch.call(node);

        byte[] patchBytes;
        try {
            // COMPUTE_FRAMES:脚本可能改控制流;GAME 层 classloader 可加载游戏类,帧重算安全
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            patchBytes = writer.toByteArray();
        } catch (RuntimeException frameFailure) {
            GroovyLog.INSTANCE.warn("Surgery.submit({}) frame recompute failed ({}), retrying without it — "
                    + "only safe if control flow was not modified", name, frameFailure.toString());
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            patchBytes = writer.toByteArray();
        }

        Path dir = SURGERIES.resolve(name);
        try {
            Files.createDirectories(dir);
            // patch.class/surgery.txt 写后即被 coremod(SurgeryStore)读取,原子写
            AtomicFiles.write(dir.resolve("patch.class"), patchBytes);
            StringBuilder manifest = new StringBuilder();
            manifest.append("# groovier surgery pack (regenerated by Surgery.submit)\n");
            manifest.append("target=").append(target).append('\n');
            manifest.append("mode=").append(exclusive ? "exclusive" : "patch_with_mixins").append('\n');
            manifest.append("preSha256=").append(sha256Hex(preBytes)).append('\n');
            manifest.append("drop=").append(String.join(",", drop)).append('\n');
            AtomicFiles.writeString(dir.resolve("surgery.txt"), manifest.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write surgery pack: " + dir, e);
        }

        GroovyLog.INSTANCE.info("Surgery pack '{}' submitted for {} ({} mode, {} drop spec(s), {} bytes) — "
                + "takes effect on next launch", name, target, exclusive ? "exclusive" : "patch_with_mixins",
                drop.size(), patchBytes.length);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("target", target);
        out.put("mode", exclusive ? "exclusive" : "patch_with_mixins");
        out.put("drop", drop);
        out.put("patchBytes", patchBytes.length);
        out.put("preSha256", sha256Hex(preBytes));
        out.put("location", dir.toString());
        return out;
    }

    /** 已安装手术包清单(name -> target/mode/drop 摘要) */
    public Map<String, Map<String, String>> list() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Path dir : packDirs()) {
            String name = dir.getFileName().toString();
            Map<String, String> info = new LinkedHashMap<>();
            Path manifest = dir.resolve("surgery.txt");
            try {
                for (String raw : Files.readAllLines(manifest)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq > 0 && List.of("target", "mode", "drop").contains(line.substring(0, eq))) {
                        info.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            } catch (IOException e) {
                info.put("error", "manifest unreadable: " + e);
            }
            info.put("hasPatch", String.valueOf(Files.isRegularFile(dir.resolve("patch.class"))));
            out.put(name, info);
        }
        return out;
    }

    /** 删除手术包(下次启动不生效);返回是否删除了存在的包 */
    public boolean remove(String name) {
        if (name == null || name.isBlank() || !isSafePackName(name)) {
            GroovyLog.INSTANCE.error("Surgery.remove({}): invalid pack name, ignored", name);
            return false;
        }
        Path dir = SURGERIES.resolve(name);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    GroovyLog.INSTANCE.error("Surgery.remove({}) failed to delete {}", name, p, e);
                }
            });
        } catch (IOException e) {
            GroovyLog.INSTANCE.error("Surgery.remove({}) walk failed", name, e);
            return false;
        }
        GroovyLog.INSTANCE.info("Surgery pack '{}' removed", name);
        return true;
    }

    private List<Path> packDirs() {
        if (!Files.isDirectory(SURGERIES)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(SURGERIES)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            GroovyLog.INSTANCE.error("Surgery.list() scan failed: {}", e);
            return List.of();
        }
    }

    private Object readNode(Path file, String phase, String targetClass) {
        if (!Files.isRegularFile(file)) {
            GroovyLog.INSTANCE.warn("Surgery.{}({}): no {} capture at {} — configure the class and launch once",
                    phase, targetClass, phase, file);
            return null;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(file)).accept(node, 0);
            return node;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to read " + phase + " capture: " + file, e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 包名须可安全用作目录名(拒绝路径分隔符、'..'、控制字符等);非法即抛出 */
    private static void validatePackName(String name) {
        if (!isSafePackName(name)) {
            throw new IllegalArgumentException("Invalid surgery pack name '" + name
                    + "': must not contain path separators, '..', ':', '<', '>' or control characters");
        }
    }

    /** 目标类名清洗:拒绝路径分隔符、'..'、'<init>' 等(泛化为 '<' '>' 任意尖括号成分) */
    private static void validateTarget(String target) {
        if (target.contains("/") || target.contains("\\") || target.contains("..")
                || target.contains("<") || target.contains(">")) {
            throw new IllegalArgumentException("Invalid surgery target '" + target
                    + "': must not contain path separators, '..', '<' or '>'");
        }
    }

    /** 目录名安全检查:无路径分隔符、无 '..'、无 Windows 保留字符与控制字符 */
    private static boolean isSafePackName(String name) {
        return name.matches("[^/\\\\:<>?\"|*\0-\u001f]+") && !name.contains("..");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
