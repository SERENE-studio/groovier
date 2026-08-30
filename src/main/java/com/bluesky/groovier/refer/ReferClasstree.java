package com.bluesky.groovier.refer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /gvr classtree:从 refer 残局字节码(local/refer/classes/)读继承关系
 * (ClassReader 头部解析,零全量 accept),生成 groovy_scripts/refer/classtree.txt。
 *
 * 格式:外部父类不缩进(非 Object 时以 "(extends X)" 注明);refer 内每层子类
 * 以 # 重复深度缩进;疑似混淆类名追加 [O] 标记。每个类恰好出现一次
 * (挂在最近的 refer 祖先之下),兄弟按名排序。
 */
public final class ReferClasstree {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferClasstree.class);

    private ReferClasstree() {}

    /** @return 聊天回执摘要(全量树) */
    public static String dump() {
        return dump(null);
    }

    /** @param filter 可选类名过滤(FQCN 不区分大小写包含匹配,如 "Zombie");null 全量
     *  @return 聊天回执摘要 */
    public static String dump(String filter) {
        Path classes = ReferExporter.classesDir();
        if (!Files.isDirectory(classes)) {
            return "No refer classes captured (create config/groovier-refer.txt with target classes and restart).";
        }
        Map<String, String> supers = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(classes)) {
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".class")).toList()) {
                try {
                    ClassReader reader = new ClassReader(Files.readAllBytes(p));
                    String className = reader.getClassName().replace('/', '.');
                    String superName = reader.getSuperName() == null ? "" : reader.getSuperName().replace('/', '.');
                    supers.put(className, superName);
                } catch (Exception e) {
                    LOGGER.warn("classtree: failed to read {}", p, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("classtree: failed to scan {}", classes, e);
            return "classtree failed: " + e;
        }
        if (filter != null && !filter.isBlank()) {
            String f = filter.toLowerCase();
            supers.entrySet().removeIf(e -> !e.getKey().toLowerCase().contains(f));
        }
        if (supers.isEmpty()) {
            return "Refer classes directory is empty"
                    + (filter == null ? "" : " or no class matched filter '" + filter + "'")
                    + " (no target class loaded this launch).";
        }

        Map<String, List<String>> children = new TreeMap<>();
        List<String> roots = new ArrayList<>();
        for (Map.Entry<String, String> entry : supers.entrySet()) {
            String sup = entry.getValue();
            if (sup.isEmpty() || !supers.containsKey(sup)) {
                roots.add(entry.getKey());
            } else {
                children.computeIfAbsent(sup, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        children.values().forEach(list -> list.sort(String::compareTo));

        StringBuilder sb = new StringBuilder();
        for (String root : roots) {
            String sup = supers.get(root);
            if (!sup.isEmpty() && !"java.lang.Object".equals(sup)) {
                sb.append(mark(root)).append(" (extends ").append(sup).append(")\n");
            } else {
                sb.append(mark(root)).append('\n');
            }
            writeSubtree(sb, root, children, 1);
        }

        try {
            Path target = ReferExporter.referRoot().resolve("classtree.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, sb.toString());
            return "classtree written: " + supers.size() + " class(es) -> groovy_scripts/refer/classtree.txt";
        } catch (Exception e) {
            LOGGER.error("classtree: failed to write output", e);
            return "classtree write failed: " + e;
        }
    }

    private static void writeSubtree(StringBuilder sb, String cls, Map<String, List<String>> children, int depth) {
        for (String child : children.getOrDefault(cls, List.of())) {
            sb.append("#".repeat(depth)).append(' ').append(mark(child)).append('\n');
            writeSubtree(sb, child, children, depth + 1);
        }
    }

    private static String mark(String className) {
        return ReferExporter.isLikelyObfuscated(className) ? className + " [O]" : className;
    }
}
