package com.bluesky.groovier.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 读取 coremod 侧产物 local/mixin_invalidated/report.json 的极简解析器。
 *
 * 不引入 JSON 库:报告由 InvalidationStore 以固定缩进格式写出,按行状态机解析。
 * 值提取转义感知(写出端经 JSON 标准转义),并兼容单行紧凑 target 行
 * (如 channel_failed:"<target>": {"status": "channel_failed"})。
 * GAME 层与 SERVICE 层零 Java 依赖(同 jar 双身份时类空间隔离,跨层只走文件产物)。
 */
final class MixinReportReader {

    record TargetReport(String target, String status, List<String> removedMixins, List<String> keptMixins) {}

    record Report(List<String> blacklistRules, List<TargetReport> targets, java.util.Map<String, String> surgeries) {}

    private MixinReportReader() {}

    static Path reportPath() {
        // coremod 侧以 GAMEDIR 为基准落盘,读端必须一致(不可用进程 cwd)
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("mixin_invalidated").resolve("report.json");
    }

    static Report read() throws IOException {
        List<String> lines = Files.readAllLines(reportPath(), StandardCharsets.UTF_8);
        List<String> rules = new ArrayList<>();
        List<TargetReport> targets = new ArrayList<>();
        String currentTarget = null;
        String currentStatus = null;
        List<String> currentMixins = null;
        List<String> currentKept = null;
        boolean inRules = false;
        boolean inMixins = false;
        boolean inSurgeries = false;
        boolean inInvalidations = false;
        java.util.Map<String, String> surgeries = new java.util.LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("\"invalidations\": {")) {
                inInvalidations = true;
                continue;
            }
            if (line.startsWith("\"surgeries\": {")) {
                inSurgeries = true;
                inInvalidations = false;
                continue;
            }
            if (inSurgeries) {
                if (line.startsWith("}")) {
                    inSurgeries = false;
                } else if (line.startsWith("\"") && line.contains("\": {")) {
                    // 单行紧凑:"<name>": {"target": "...", "status": "..."}
                    String name = line.substring(1, line.indexOf("\":"));
                    String target = extractJsonField(line, "\"target\": \"");
                    String status = extractJsonField(line, "\"status\": \"");
                    surgeries.put(name, target + " | " + status);
                }
                continue;
            }
            if (line.startsWith("\"blacklistRules\": [")) {
                inRules = true;
                continue;
            }
            if (inRules) {
                if (line.startsWith("]")) {
                    inRules = false;
                } else {
                    rules.add(unquote(line));
                }
                continue;
            }
            if (line.startsWith("\"removedMixins\": {")) {
                inMixins = true;
                continue;
            }
            if (line.startsWith("\"keptMixins\": {")) {
                // 手术模式保留注册,单行紧凑 JSON
                currentKept = parseInlineKeys(line);
                continue;
            }
            if (inMixins) {
                if (line.startsWith("}")) {
                    inMixins = false;
                } else {
                    currentMixins.add(unquoteKey(line));
                }
                continue;
            }
            if (line.startsWith("\"status\":")) {
                currentStatus = unquoteValue(line);
                continue;
            }
            // invalidations 块内的 target 键行:4 空格缩进,两种形态:
            //   多行:  "target": {                        (摘除明细展开在后继行)
            //   单行:  "target": {"status": "..."}        (如 channel_failed 紧凑输出)
            // 仅限 invalidations 块(pins/overrides 块同为 4 空格单行,须排除)
            if (inInvalidations && raw.startsWith("    \"") && line.contains("\": {")) {
                if (currentTarget != null) {
                    targets.add(new TargetReport(currentTarget, currentStatus, currentMixins, currentKept));
                }
                currentTarget = line.substring(0, line.lastIndexOf("\":")).replace("\"", "").trim();
                currentStatus = null;
                currentMixins = new ArrayList<>();
                currentKept = List.of();
                if (!line.endsWith("{")) {
                    // 单行紧凑形态:status 内嵌于同一行
                    currentStatus = extractJsonField(line, "\"status\": \"");
                }
            }
        }
        if (currentTarget != null) {
            targets.add(new TargetReport(currentTarget, currentStatus, currentMixins, currentKept));
        }
        return new Report(rules, targets, surgeries);
    }

    /** 单行 JSON 字段值提取:从 keyPrefix 后的字符串字面量读值(转义感知 + 反转义) */
    private static String extractJsonField(String line, String keyPrefix) {
        int start = line.indexOf(keyPrefix);
        if (start < 0) {
            return "";
        }
        return extractString(line, start + keyPrefix.length());
    }

    /**
     * 转义感知的 JSON 字符串值读取:从 from(开引号之后)扫描到未转义的闭引号,
     * 处理写出端 {@code \"}、{@code \\}、{@code \n} 等标准转义序列。
     */
    static String extractString(String s, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(n); // \" \\ \/
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 解析单行 "keptMixins": {"a": "cfg", ...} 的键列表 */
    private static List<String> parseInlineKeys(String line) {
        int open = line.indexOf('{');
        int close = line.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : line.substring(open + 1, close).split(", ")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf("\":");
            out.add(idx > 0 ? trimmed.substring(0, idx).replace("\"", "").trim() : trimmed);
        }
        return out;
    }

    private static String unquote(String line) {
        String s = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return extractString(s, 1);
        }
        return s;
    }

    private static String unquoteKey(String line) {
        String s = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
        int idx = s.indexOf("\":");
        String key = idx > 0 ? s.substring(0, idx) : s;
        return unquote(key.trim());
    }

    private static String unquoteValue(String line) {
        int idx = line.indexOf(':');
        if (idx < 0) {
            return "";
        }
        String rest = line.substring(idx + 1).trim();
        if (rest.endsWith(",")) {
            rest = rest.substring(0, rest.length() - 1).trim();
        }
        if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
            return extractString(rest, 1);
        }
        return rest;
    }
}
