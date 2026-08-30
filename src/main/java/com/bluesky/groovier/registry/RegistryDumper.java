package com.bluesky.groovier.registry;

import com.bluesky.groovier.api.GroovyLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * /gvr register:全量通用枚举注册表 dump(开发者服务)。
 * - 静态注册表:遍历 BuiltInRegistries.REGISTRY(根注册表,含 NeoForge 注入的模组自建注册表);
 * - 动态/数据包注册表:server.registryAccess().registries()(structure/enchantment/biome 等在此);
 * - 输出:local/register/<registry>.json + <registry>.csv(UTF-8 BOM)+ index.csv 总览;
 * - 后台线程执行(注册表冻结后只读,并发读安全),完成后回 server thread 发聊天回执;
 * - 单表写入异常隔离,不中断其余表。
 */
public final class RegistryDumper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RegistryDumper() {
    }

    /**
     * 命令入口。
     *
     * @param type   注册表短名(item)或完整 key(minecraft:item / neoforge:attachment_type),null = 全部
     * @param filter namespace 或路径子串过滤(不区分大小写),null/空 = 不过滤
     */
    public static void dump(CommandSourceStack source, String type, String filter) {
        // 命令线程(server thread)捕获引用,后台线程只做只读遍历与文件写入
        final RegistryAccess access = source.getServer().registryAccess();
        final MinecraftServer server = source.getServer();
        final String filterLower = (filter == null || filter.isBlank()) ? null : filter.toLowerCase();

        List<RegistryEntry> targets = collectRegistries(access);
        if (type != null && !type.isBlank()) {
            List<RegistryEntry> matched = matchRegistries(targets, type);
            if (matched.isEmpty()) {
                StringBuilder sb = new StringBuilder("Registry not found: ").append(type).append(". Candidates: ");
                for (RegistryEntry e : targets) sb.append(e.shortName()).append(' ');
                source.sendFailure(Component.literal(sb.toString().trim()));
                return;
            }
            targets = matched;
        }

        final List<RegistryEntry> dumpTargets = targets;
        source.sendSuccess(() -> Component.literal("Dumping " + dumpTargets.size() + " registries to local/register/ in the background..."), true);
        CompletableFuture.runAsync(() -> {
            String timestamp = Instant.now().toString();
            String mcVersion = SharedConstants.getCurrentVersion().getName();
            // 以 GAMEDIR 为基准(进程 cwd 在生产环境不一定是游戏目录)
            File outDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("local").resolve("register").toFile();
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                sendResult(source, server, "Failed to create directory " + outDir.getAbsolutePath(), true);
                return;
            }
            List<String> failed = new ArrayList<>();
            int dumped = 0, entries = 0;
            for (RegistryEntry entry : dumpTargets) {
                try {
                    int count = dumpOne(entry, outDir, mcVersion, timestamp, filterLower);
                    dumped++;
                    entries += count;
                } catch (Throwable t) {
                    GroovyLog.INSTANCE.error("Failed to dump registry {}: {}", entry.registryName(), t);
                    failed.add(entry.shortName());
                }
            }
            try {
                rebuildIndex(outDir);
            } catch (Throwable t) {
                GroovyLog.INSTANCE.error("Failed to rebuild index.csv: {}", t);
                failed.add("index.csv");
            }
            String msg = "Dumped " + dumped + " registries (" + entries + " entries) to local/register/"
                    + (failed.isEmpty() ? "" : "; failed: " + String.join(", ", failed));
            sendResult(source, server, msg, !failed.isEmpty());
        });
    }

    // ------------------------------------------------------------------ 数据收集

    /**
     * 全部目标注册表:静态(根注册表枚举,跳过根自身)+ 动态(registryAccess)。
     * 去重规则:RegistryAccess.registries() 会连静态注册表一起返回 —— 同 key 同实例 → 跳过(保留 static 标记);
     * 同 key 不同实例(静态侧是 vanilla 空占位,如 worldgen/structure)→ 用 access 实例替换并标 dynamic(有真实数据)。
     */
    private static List<RegistryEntry> collectRegistries(RegistryAccess access) {
        Map<ResourceLocation, RegistryEntry> merged = new LinkedHashMap<>();
        for (var e : BuiltInRegistries.REGISTRY.entrySet()) {
            Registry<?> reg = e.getValue();
            if (reg == BuiltInRegistries.REGISTRY) continue; // 根注册表自身不是数据,跳过
            merged.put(e.getKey().location(), new RegistryEntry(e.getKey().location(), reg, "static"));
        }
        access.registries().forEach(entry -> {
            ResourceLocation loc = entry.key().location();
            Registry<?> accessReg = entry.value();
            RegistryEntry existing = merged.get(loc);
            if (existing == null || existing.registry() != accessReg) {
                merged.put(loc, new RegistryEntry(loc, accessReg, "dynamic"));
            }
        });
        return new ArrayList<>(merged.values());
    }

    /** type 匹配:短名/完整 key 精确(忽略大小写)优先,无精确命中时回退子串包含。 */
    private static List<RegistryEntry> matchRegistries(List<RegistryEntry> targets, String type) {
        List<RegistryEntry> exact = new ArrayList<>();
        List<RegistryEntry> partial = new ArrayList<>();
        String lower = type.toLowerCase();
        for (RegistryEntry e : targets) {
            if (e.shortName().equalsIgnoreCase(lower) || e.registryName().toString().equalsIgnoreCase(lower)) {
                exact.add(e);
            } else if (e.shortName().contains(lower) || e.registryName().getPath().contains(lower)) {
                partial.add(e);
            }
        }
        return exact.isEmpty() ? partial : exact;
    }

    // ------------------------------------------------------------------ 单表 dump

    /** dump 单个注册表,返回条目数。 */
    private static int dumpOne(RegistryEntry entry, File outDir, String mcVersion, String timestamp,
                               String filter) throws IOException {
        // 文件名含 namespace(dumpName),避免不同 namespace 同 path 的注册表互相覆盖;过滤产物独立后缀文件
        String fileName = entry.dumpName() + (filter == null ? "" : "__" + filterTag(filter));
        String registryKeyStr = entry.registryName().toString();
        JsonObject json = new JsonObject();
        json.addProperty("registry_key", registryKeyStr);
        json.addProperty("short_name", entry.shortName());
        json.addProperty("scope", entry.scope());
        json.addProperty("mc_version", mcVersion);
        json.addProperty("timestamp", timestamp);
        if (filter != null) json.addProperty("filter", filter);

        JsonObject rows = new JsonObject();
        List<String> csvRows = new ArrayList<>();
        csvRows.add("registry,namespace,path,numeric_id,translation_key,java_class,tags,cross_ref");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Registry raw = entry.registry();
        int count = 0;
        for (Object rawEntry : raw.entrySet()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) rawEntry;
            ResourceLocation id = ((ResourceKey<?>) e.getKey()).location();
            if (filter != null && !id.toString().contains(filter)) continue;
            Object value = e.getValue();
            String translationKey = translationKeyOf(value);
            String tags = tagsOf(raw, (ResourceKey) e.getKey());
            String crossRef = crossRefOf(value);
            int numericId = raw.getId(value);

            JsonObject row = new JsonObject();
            row.addProperty("numeric_id", numericId);
            row.addProperty("translation_key", translationKey);
            row.addProperty("java_class", value.getClass().getSimpleName());
            row.add("tags", stringArray(tags.isEmpty() ? List.of() : List.of(tags.split(";"))));
            if (!crossRef.isEmpty()) row.add("binds", bindsJson(crossRef));
            rows.add(id.toString(), row);

            csvRows.add(String.join(",",
                    csvEscape(registryKeyStr),
                    csvEscape(id.getNamespace()),
                    csvEscape(id.getPath()),
                    String.valueOf(numericId),
                    csvEscape(translationKey),
                    csvEscape(value.getClass().getSimpleName()),
                    csvEscape(tags),
                    csvEscape(crossRef)));
            count++;
        }
        json.addProperty("count", count);
        json.add("entries", rows);

        Files.writeString(new File(outDir, fileName + ".json").toPath(), GSON.toJson(json), StandardCharsets.UTF_8);
        Files.writeString(new File(outDir, fileName + ".csv").toPath(), toCsv(csvRows), StandardCharsets.UTF_8);
        return count;
    }

    /** filter → 文件名安全短标(小写,非法字符转 '-',限 40 字符)。 */
    private static String filterTag(String filter) {
        String tag = filter.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return tag.length() > 40 ? tag.substring(0, 40) : tag;
    }

    /**
     * index.csv 重建:扫描目录内全部 *.json 的表头(registry_key/short_name/scope/count/filter),
     * 与本次运行范围无关 —— 任何子集 dump 后 index 始终是目录全貌。
     */
    private static void rebuildIndex(File outDir) throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("registry_key,short_name,scope,count,filter,file");
        File[] files = outDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                try {
                    JsonObject header = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                    rows.add(String.join(",",
                            csvEscape(str(header, "registry_key")),
                            csvEscape(str(header, "short_name")),
                            csvEscape(str(header, "scope")),
                            String.valueOf(header.has("count") ? header.get("count").getAsInt() : 0),
                            csvEscape(str(header, "filter")),
                            csvEscape(f.getName())));
                } catch (Throwable t) {
                    GroovyLog.INSTANCE.warn("Skipping unparseable register dump {}: {}", f.getName(), t);
                }
            }
        }
        Files.writeString(new File(outDir, "index.csv").toPath(), toCsv(rows), StandardCharsets.UTF_8);
    }

    /** JsonObject 字符串字段容错读取(缺失返回空串)。 */
    private static String str(JsonObject obj, String field) {
        return obj != null && obj.has(field) && obj.get(field).isJsonPrimitive() ? obj.get(field).getAsString() : "";
    }

    // ------------------------------------------------------------------ 字段提取

    /** 翻译键:常见类型走 instanceof 链,其余留空(动态注册表多为 record,无通用 API)。 */
    private static String translationKeyOf(Object value) {
        if (value instanceof Block block) return block.getDescriptionId();
        if (value instanceof ItemLike itemLike) return itemLike.asItem().getDescriptionId();
        if (value instanceof EntityType<?> entityType) return entityType.getDescriptionId();
        if (value instanceof MobEffect effect) return effect.getDescriptionId();
        return "";
    }

    /** tags:经 holder 读取(非 holder 支撑的注册表留空,异常吞掉不影响其余字段)。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tagsOf(Registry registry, ResourceKey key) {
        try {
            Holder holder = registry.getHolderOrThrow(key);
            List<String> tags = new ArrayList<>();
            for (Object tag : (java.util.Collection<?>) holder.tags()) {
                tags.add(((net.minecraft.tags.TagKey<?>) tag).location().toString());
            }
            tags.sort(String::compareTo);
            return String.join(";", tags);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 交叉引用:item↔block、spawn egg→entity,尽力而为。多绑定以 ';' 分隔。 */
    private static String crossRefOf(Object value) {
        List<String> refs = new ArrayList<>();
        try {
            if (value instanceof BlockItem blockItem) {
                refs.add("block=" + BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()));
            } else if (value instanceof Block block) {
                Item item = Item.BY_BLOCK.get(block);
                if (item != null) refs.add("item=" + BuiltInRegistries.ITEM.getKey(item));
            }
            if (value instanceof SpawnEggItem spawnEgg) {
                refs.add("entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(spawnEgg.getType(ItemStack.EMPTY)));
            }
        } catch (Throwable ignored) {
            // best-effort:交叉引用提取失败不影响条目主字段
        }
        return String.join(";", refs);
    }

    /** cross_ref 字符串 → JSON binds 对象(key=value 对,key 作字段名)。 */
    private static JsonObject bindsJson(String crossRef) {
        JsonObject binds = new JsonObject();
        for (String ref : crossRef.split(";")) {
            int idx = ref.indexOf('=');
            if (idx > 0) binds.addProperty(ref.substring(0, idx), ref.substring(idx + 1));
        }
        return binds;
    }

    // ------------------------------------------------------------------ 小工具

    private static JsonArray stringArray(List<String> values) {
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        return arr;
    }

    /** CSV 行组装:UTF-8 BOM 前置(Excel 中文友好)。 */
    private static String toCsv(List<String> rows) {
        return "\uFEFF" + String.join("\r\n", rows) + "\r\n";
    }

    private static String csvEscape(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }

    /** dump 结果回 server thread 发送(后台线程不可直接碰聊天组件)。 */
    private static void sendResult(CommandSourceStack source, MinecraftServer server, String msg, boolean failure) {
        Runnable task = failure
                ? () -> source.sendFailure(Component.literal(msg))
                : () -> source.sendSuccess(() -> Component.literal(msg), true);
        if (server != null && !server.isStopped()) {
            server.execute(task);
        }
        if (failure) GroovyLog.INSTANCE.error(msg);
    }

    // ------------------------------------------------------------------ 内部模型

    /** 一个待 dump 注册表的元数据:注册表 key 位置 + 注册表实例 + 静态/动态标记。 */
    private record RegistryEntry(ResourceLocation registryName, Registry<?> registry, String scope) {

        String shortName() {
            return registryName.getPath().replace('/', '_');
        }

        /** 落盘文件名:含 namespace,跨 namespace 同 path 的注册表不再互相覆盖 */
        String dumpName() {
            return registryName.getNamespace().replace('/', '_') + "_" + shortName();
        }
    }
}
