package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * mixin 作废黑名单(启动早期读取,SERVICE 层)。
 *
 * 文件: config/groovier-mixin-blacklist.txt(UTF-8)
 * 语法: 每行一条;# 开头为注释;空行忽略;
 *       类级规则  com.example.Foo            = 摘除该类上全部 mixin
 *       类级前缀  com.example.*              = 展开为 mods jar 内全部匹配类,各自全摘
 *       手术规则  target::com.foo.SomeMixin  = 仅摘除 target 上由指定 mixin 类贡献的注册
 *       手术混合  com.example.*::com.foo.*   = 两侧均支持包前缀(mixin 侧按类名前缀匹配)
 *
 * 手术语义(0.15.2 反编译确认):MixinConfig.mixinMapping 的 value 是 List&lt;MixinInfo&gt;,
 * 按项移除即可保留同 target 上的其余 mixin("选择性保留"通道)。
 *
 * 注:对齐 SPEC 6.5 的 yaml 决策推迟到 §10.2 统一 config 时;
 *    此处采用零依赖行式格式,保证 SERVICE 层(无 Gson/SnakeYAML 保证)可用。
 */
final class MixinBlacklist {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.MixinBlacklist");
    private static final String FILE_NAME = "groovier-mixin-blacklist.txt";
    private static final String SURGICAL_SEP = "::";

    /** 原始规则(诊断报告用) */
    private static final List<String> RAW_RULES = new ArrayList<>();
    /** 展开后的全部精确目标类名(点分),transformer targets 的来源 */
    private static volatile Set<String> resolvedTargets = Set.of();
    /** 类级规则覆盖的目标(全摘) */
    private static volatile Set<String> removeAllTargets = Set.of();
    /** 手术规则:target -> mixin 规格列表(精确名或 prefix.*) */
    private static volatile Map<String, List<String>> surgicalSpecs = Map.of();

    private MixinBlacklist() {}

    /** 一条规则:targetSpec 精确名或 prefix.*;mixinSpec 同,为 null 表示类级 */
    private record Rule(String targetSpec, String mixinSpec) {}

    static void load(IEnvironment environment) {
        // GAMEDIR 定位统一走 CoremodFiles(IEnvironment 键 → FMLPaths 反射);
        // 不回退 user.dir:进程 cwd 与 GAMEDIR 不一致时会读到错误位置的黑名单与 mods 目录
        Path gameDir = CoremodFiles.gameDir(environment);
        if (gameDir == null) {
            LOGGER.error("Groovier GAMEDIR unavailable, mixin invalidation disabled for this launch");
            resolvedTargets = Set.of();
            removeAllTargets = Set.of();
            surgicalSpecs = Map.of();
            return;
        }
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
                LOGGER.error("Groovier mixin blacklist read failed, invalidation disabled: {}", configFile, e);
            }
        } else {
            LOGGER.info("Groovier mixin blacklist not present ({}), mixin invalidation disabled", configFile);
        }

        RAW_RULES.clear();
        RAW_RULES.addAll(rules);
        if (rules.isEmpty()) {
            resolvedTargets = Set.of();
            removeAllTargets = Set.of();
            surgicalSpecs = Map.of();
            return;
        }

        List<Rule> parsed = new ArrayList<>();
        for (String rule : rules) {
            Rule r = parseRule(rule);
            if (r == null) {
                LOGGER.warn("Invalid blacklist rule ignored: {}", rule);
            } else {
                parsed.add(r);
            }
        }

        List<Rule> classRules = parsed.stream().filter(r -> r.mixinSpec() == null).toList();
        List<Rule> surgicalRules = parsed.stream().filter(r -> r.mixinSpec() != null).toList();

        // 前缀展开:一次扫描喂给类级与手术两侧
        Set<String> classPrefixes = specPrefixes(classRules.stream().map(Rule::targetSpec).toList());
        Set<String> surgicalPrefixes = specPrefixes(surgicalRules.stream().map(Rule::targetSpec).toList());
        Set<String> allPrefixes = new LinkedHashSet<>(classPrefixes);
        allPrefixes.addAll(surgicalPrefixes);
        Map<String, Set<String>> expanded = expandPrefixes(gameDir.resolve("mods"), allPrefixes);

        Set<String> allTargets = new LinkedHashSet<>();
        Set<String> removeall = new LinkedHashSet<>();
        for (Rule r : classRules) {
            for (String t : resolveSpec(r.targetSpec(), expanded)) {
                allTargets.add(t);
                removeall.add(t);
            }
        }
        Map<String, List<String>> surgical = new LinkedHashMap<>();
        for (Rule r : surgicalRules) {
            for (String t : resolveSpec(r.targetSpec(), expanded)) {
                allTargets.add(t);
                surgical.computeIfAbsent(t, k -> new ArrayList<>()).add(r.mixinSpec());
            }
        }
        // 同一目标既有类级规则又有手术规则:类级(全摘)覆盖手术表
        removeAllTargets = Set.copyOf(removeall);
        surgical.keySet().removeAll(removeall);
        surgicalSpecs = Map.copyOf(surgical);
        resolvedTargets = Set.copyOf(allTargets);
        LOGGER.info("Groovier mixin blacklist loaded: {} rule(s) -> {} target class(es) ({} class-level, {} surgical)",
                rules.size(), allTargets.size(), removeall.size(), surgical.size());
    }

    static List<String> rawRules() {
        return List.copyOf(RAW_RULES);
    }

    static Set<String> resolvedTargets() {
        return resolvedTargets;
    }

    /**
     * @return null = 该目标被类级规则覆盖(全摘);空列表 = 无规则(不应出现在 transformer targets 中);
     *         非空 = 手术规格列表(仅摘匹配的 mixin)
     */
    static List<String> mixinSpecsFor(String targetClassName) {
        if (removeAllTargets.contains(targetClassName)) {
            return null;
        }
        return surgicalSpecs.getOrDefault(targetClassName, List.of());
    }

    /** mixin 类名是否命中规格(精确名,或 prefix.*) */
    static boolean matchesSpec(String mixinClassName, String spec) {
        if (spec.endsWith(".*")) {
            return mixinClassName.startsWith(spec.substring(0, spec.length() - 2) + ".");
        }
        return mixinClassName.equals(spec);
    }

    /** 供 PostWatch 等复用的前缀展开:mods jar 类名扫描,返回 prefix -> 匹配类名集合 */
    static Map<String, Set<String>> expandPrefixTargets(Path modsDir, Set<String> prefixes) {
        return expandPrefixes(modsDir, prefixes);
    }

    private static Rule parseRule(String rule) {
        String targetSpec;
        String mixinSpec;
        if (rule.contains(SURGICAL_SEP)) {
            String[] parts = rule.split(SURGICAL_SEP, -1);
            if (parts.length != 2) {
                return null;
            }
            targetSpec = parts[0].trim();
            mixinSpec = parts[1].trim();
            if (!validSpec(mixinSpec)) {
                return null;
            }
        } else {
            targetSpec = rule;
            mixinSpec = null;
        }
        return validSpec(targetSpec) ? new Rule(targetSpec, mixinSpec) : null;
    }

    private static boolean validSpec(String spec) {
        if (spec.isEmpty() || spec.contains(" ") || spec.equals("*") || spec.endsWith(".")) {
            return false;
        }
        if (spec.endsWith(".*")) {
            String prefix = spec.substring(0, spec.length() - 2);
            return !prefix.isEmpty() && !prefix.contains("*");
        }
        return !spec.contains("*");
    }

    private static Set<String> specPrefixes(List<String> specs) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : specs) {
            if (s.endsWith(".*")) {
                out.add(s.substring(0, s.length() - 2));
            }
        }
        return out;
    }

    private static Set<String> resolveSpec(String spec, Map<String, Set<String>> expanded) {
        if (spec.endsWith(".*")) {
            return expanded.getOrDefault(spec.substring(0, spec.length() - 2), Set.of());
        }
        return Set.of(spec);
    }

    /** 扫 mods 目录全部 jar 的类名,返回 prefix -> 匹配类名集合(点分) */
    private static Map<String, Set<String>> expandPrefixes(Path modsDir, Set<String> prefixes) {
        Map<String, Set<String>> out = new HashMap<>();
        if (prefixes.isEmpty()) {
            return out;
        }
        if (!Files.isDirectory(modsDir)) {
            LOGGER.warn("Mods dir not found for blacklist prefix expansion: {}", modsDir);
            return out;
        }
        try (var stream = Files.list(modsDir)) {
            List<Path> jars = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList();
            for (Path jar : jars) {
                scanJar(jar, prefixes, out);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list mods dir for blacklist expansion: {}", modsDir, e);
        }
        return out;
    }

    private static void scanJar(Path jar, Set<String> prefixes, Map<String, Set<String>> out) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                String dot = entry.getName().substring(0, entry.getName().length() - ".class".length())
                        .replace('/', '.');
                // 不跳过内部类:黑名单按用户声明生效,内部类单列即可
                // 不 break:父前缀与子前缀可同时声明(如 com.example.* 与 com.example.util.*),
                // 命中首个前缀后继续匹配其余前缀,保证所有匹配的规则都被展开
                for (String prefix : prefixes) {
                    if (dot.startsWith(prefix + ".")) {
                        out.computeIfAbsent(prefix, k -> new LinkedHashSet<>()).add(dot);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan jar for blacklist expansion: {}", jar, e);
        }
    }
}
