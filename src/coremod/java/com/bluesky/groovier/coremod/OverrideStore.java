package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * 6.4 整类替换(SPEC §6.4,6.5 覆盖闭环的执行端)。
 *
 * <p>文件: config/groovier-override.txt(UTF-8)
 * 语法: 每行一条;# 注释;精确类名或包前缀 prefix.*(与 groovier-refer.txt 同构)。
 *
 * <p>通路: GAME 层 OverrideManager 扫描 groovy_scripts/override/*.groovy 影子编译,
 * 经 ApiDiff 签名契约校验后落盘 local/override/classes/&lt;fqn&gt;.class;本类在目标类
 * coprocessor 回调(mixin 应用后、define 前)读取该文件,把 override 字节整体换入
 * ClassNode(残局替换,丢弃其他模组对该类的字节码改动 = 6.4.1 明示取舍)。
 * 跨层只走文件产物,不做 SERVICE/GAME 类空间互通(PinningTransformer 桥接同理由)。
 *
 * <p>fail-safe:任何异常恢复原 ClassNode 内容返回 false,类按原身加载,报告标 error。
 * 与钉子互斥:PinningTransformer 在 pre-mixin 阶段注入的包装会被整类替换丢弃,发现
 * 同类同时存在钉子时在状态中告警。
 */
final class OverrideStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.OverrideStore");
    private static final String FILE_NAME = "groovier-override.txt";

    /** 原始规则(诊断用) */
    private static final Set<String> RAW_RULES = new LinkedHashSet<>();
    /** 精确目标类名 */
    private static volatile Set<String> exactTargets = Set.of();
    /** 包前缀(尾点匹配) */
    private static volatile Set<String> prefixes = Set.of();
    /** coprocessor 注入触发器用的具体目标(exact + 前缀展开) */
    private static volatile Set<String> triggerTargets = Set.of();
    private static Path inDir;

    private OverrideStore() {}

    static void load(IEnvironment environment) {
        // SERVICE 层 onLoad 时 FML 尚未注入 GAMEDIR;回退 user.dir(dev 运行的工作目录即 run/)
        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseGet(() -> Path.of(System.getProperty("user.dir")));
        inDir = gameDir.resolve("local").resolve("override").resolve("classes");
        Path configFile = gameDir.resolve("config").resolve(FILE_NAME);
        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        if (Files.exists(configFile)) {
            try {
                for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (trimmed.contains(" ") || trimmed.equals("*") || trimmed.endsWith(".")
                            || trimmed.contains("::")) {
                        LOGGER.warn("Invalid override rule ignored: {}", trimmed);
                    } else if (trimmed.endsWith(".*")) {
                        prefixes.add(trimmed.substring(0, trimmed.length() - 2));
                    } else {
                        exact.add(trimmed);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Groovier override read failed, override disabled: {}", configFile, e);
            }
        } else {
            LOGGER.info("Groovier override config not present ({}), override disabled", configFile);
        }
        RAW_RULES.clear();
        RAW_RULES.addAll(exact);
        RAW_RULES.addAll(prefixes.stream().map(p -> p + ".*").toList());
        exactTargets = Set.copyOf(exact);
        prefixes = Set.copyOf(prefixes);
        Set<String> trigger = new LinkedHashSet<>(exact);
        if (!prefixes.isEmpty()) {
            MixinBlacklist.expandPrefixTargets(gameDir.resolve("mods"), prefixes)
                    .values()
                    .forEach(trigger::addAll);
        }
        triggerTargets = Set.copyOf(trigger);
        LOGGER.info("Groovier override loaded: {} rule(s) -> {} exact / {} prefix, {} expanded trigger target(s)",
                RAW_RULES.size(), exact.size(), prefixes.size(), trigger.size());
    }

    static Set<String> rawRules() {
        return Set.copyOf(RAW_RULES);
    }

    static Set<String> resolvedTriggerTargets() {
        return triggerTargets;
    }

    static boolean hasRules() {
        return !RAW_RULES.isEmpty();
    }

    private static boolean isTarget(String className) {
        if (exactTargets.contains(className)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** coprocessor 残局替换(mixin 应用后、define 前);@return true = ClassNode 已被替换 */
    static boolean inspectPost(String className, ClassNode node) {
        if (!isTarget(className)) {
            return false;
        }
        try {
            byte[] bytes = readOverrideBytes(className);
            if (bytes == null) {
                String detail = PinStore.pinsFor(className).isEmpty()
                        ? "no override bytecode (bind pending/blocked, see local/override/bind.txt)"
                        : "no override bytecode; WARNING " + PinStore.pinsFor(className).size()
                                + " pin pack(s) exist but will be discarded by class override";
                InvalidationStore.recordOverride(className, detail);
                LOGGER.warn("Groovier override: {} has no bound bytecode ({}); loading original class",
                        className, detail);
                return false;
            }
            if (!PinStore.pinsFor(className).isEmpty()) {
                LOGGER.warn("Groovier override: {} replaces class with {} pin pack(s) injected pre-mixin "
                        + "(pin wrappers discarded; keep them replicated in the override class if needed)",
                        className, PinStore.pinsFor(className).size());
            }
            ClassNode override = new ClassNode();
            new ClassReader(bytes).accept(override, 0);
            String overrideFqn = override.name.replace('/', '.');
            if (!overrideFqn.equals(className)) {
                InvalidationStore.recordOverride(className, "error (FQN mismatch: " + override.name + ")");
                LOGGER.error("Groovier override: bound bytecode FQN mismatch, expected {} got {}", className,
                        override.name);
                return false;
            }
            applySwap(node, override, className);
            int pinPacks = PinStore.pinsFor(className).size();
            InvalidationStore.recordOverride(className,
                    "applied" + (pinPacks == 0 ? "" : "; WARNING " + pinPacks
                            + " pin pack(s) exist but will be discarded by class override"));
            LOGGER.info("Groovier override applied: {} ({} method(s), {} field(s))",
                    className, override.methods.size(), override.fields.size());
            return true;
        } catch (Throwable t) {
            LOGGER.error("Groovier override failed on {}, loading original class", className, t);
            InvalidationStore.recordOverride(className, "error (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /**
     * 整类替换:override ClassNode 的结构内容换入残局 node(类名强制保留目标名)。
     * 自引用超类修正:覆盖类 extends 目标自身(refer 复刻写法)→ 改指原父类。
     * nestMembers 保留原类清单(Groovy 独立编译产物无 nest 属性;内部类仍在原 jar,
     * nestmate 私有访问校验依赖 host 的 NestMembers 一致)。
     */
    private static void applySwap(ClassNode node, ClassNode override, String className) {
        // 备份引用,fail-safe 恢复
        var oldFields = node.fields;
        var oldMethods = node.methods;
        String oldSuper = node.superName;
        var oldInterfaces = node.interfaces;
        try {
            if (className.equals(override.superName)) {
                LOGGER.warn("Groovier override: {} extends itself (replica written against original class), "
                        + "redirecting superName to original parent {}", className, node.superName);
                override.superName = node.superName;
            }
            node.version = override.version;
            node.access = override.access;
            node.superName = override.superName;
            node.interfaces = override.interfaces;
            node.signature = override.signature;
            node.sourceFile = override.sourceFile;
            node.fields = override.fields;
            node.methods = override.methods;
            node.innerClasses = override.innerClasses;
            node.visibleAnnotations = override.visibleAnnotations;
            node.invisibleAnnotations = override.invisibleAnnotations;
            node.permittedSubclasses = override.permittedSubclasses;
            if (override.nestMembers != null && !override.nestMembers.isEmpty()) {
                node.nestMembers = override.nestMembers;
            }
            if (node.nestHostClass == null) {
                node.nestHostClass = override.nestHostClass;
            }
        } catch (Throwable t) {
            node.fields = oldFields;
            node.methods = oldMethods;
            node.superName = oldSuper;
            node.interfaces = oldInterfaces;
            throw t;
        }
    }

    private static byte[] readOverrideBytes(String className) {
        Path dir = inDir;
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(className.replace('.', '/') + ".class");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.error("Groovier override: bound bytecode read failed for {}", className, e);
            return null;
        }
    }
}
