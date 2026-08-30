package com.bluesky.groovier.api;

import com.bluesky.groovier.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import net.neoforged.fml.loading.FMLPaths;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * globals 可选持久化:变更时置脏,守护线程每 5s 去抖落盘,JVM 关停钩子补写最后一次。
 * 落盘 {@code local/globals.json}(GAMEDIR 基准,原子写);启动时静默恢复(mod 构造期,早于脚本执行)。
 *
 * <p>持久化范围(白名单深校验,防经反射泄露游戏对象/内部状态):
 * 值及其嵌套元素只能是 String/Boolean/Integer/Long/Double/Float/Short/Byte/BigDecimal/BigInteger/List/Map(Map 键须 String);
 * {@code groovier.} 命名空间(含脚本启停键 groovier.enabled.*,启停语义 = 会话级)不落盘。
 */
public final class GlobalPersistence {

    // serializeNulls:null 值键跨重启保留 contains 语义
    // LONG_OR_DOUBLE + normalizeNumbers:防 Gson Object 还原把整数漂移成 Double(1 → 1.0,switch/instanceof 失效)
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().serializeNulls()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();
    private static final long FLUSH_SECONDS = 5;
    private static final int MAX_DEPTH = 8;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    // save 互斥锁:关停钩子必须等在途落盘完成后再补写,否则守护线程会在 move 前被 halt 杀掉丢写
    private static final Object SAVE_LOCK = new Object();

    private GlobalPersistence() {}

    /** 恢复快照并启动周期落盘(幂等;在脚本执行前调用)。 */
    public static synchronized void install() {
        if (!STARTED.compareAndSet(false, true)) return;
        GlobalManager gm = GlobalManager.INSTANCE;
        restoreFromDisk(gm);
        gm.addChangeListener(GlobalPersistence::markDirty);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Groovier-Globals-Persist");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(GlobalPersistence::flushIfDirty, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(GlobalPersistence::flushIfDirty, "Groovier-Globals-Persist-Shutdown"));
    }

    private static void markDirty() {
        DIRTY.set(true);
    }

    private static void flushIfDirty() {
        synchronized (SAVE_LOCK) {
            if (!DIRTY.compareAndSet(true, false)) return;
            try {
                save();
            } catch (Throwable t) {
                DIRTY.set(true); // 下个周期重试
                GroovyLog.INSTANCE.warn("globals persistence failed, will retry: {}", t.toString());
            }
        }
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("local").resolve("globals.json");
    }

    private static void save() throws Exception {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : GlobalManager.INSTANCE.asMap().entrySet()) {
            if (e.getKey().startsWith("groovier.")) continue;
            if (!isPersistable(e.getValue(), 0)) {
                GroovyLog.INSTANCE.warn("globals key '{}' not persistable (only String/Number/Boolean/List/Map), skipped", e.getKey());
                continue;
            }
            snap.put(e.getKey(), e.getValue());
        }
        Path path = file();
        if (snap.isEmpty() && !Files.exists(path)) return; // 无内容且无历史文件,不生成
        Files.createDirectories(path.getParent());
        AtomicFiles.writeString(path, GSON.toJson(snap));
    }

    private static void restoreFromDisk(GlobalManager gm) {
        Path path = file();
        if (!Files.exists(path)) return;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, Object> restored = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                if (e.getKey().startsWith("groovier.")) continue;
                restored.put(e.getKey(), normalizeNumbers(GSON.fromJson(e.getValue(), Object.class), 0));
            }
            gm.restoreSilently(restored);
            GroovyLog.INSTANCE.info("globals restored {} key(s) from local/globals.json", restored.size());
        } catch (Throwable t) {
            GroovyLog.INSTANCE.error("globals persistence restore failed (starting with empty globals): {}", t.toString());
        }
    }

    /** 整数归一:Gson 数字还原为 Long/Double,把可无损收窄的整值归回 Integer,保住脚本 switch/instanceof 语义。 */
    private static Object normalizeNumbers(Object v, int depth) {
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) <= Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        if (depth < MAX_DEPTH && v instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object o : list) out.add(normalizeNumbers(o, depth + 1));
            return out;
        }
        if (depth < MAX_DEPTH && v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k) out.put(k, normalizeNumbers(e.getValue(), depth + 1));
            }
            return out;
        }
        return v;
    }

    private static boolean isPersistable(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Boolean
                || v instanceof Integer || v instanceof Long || v instanceof Double
                || v instanceof Float || v instanceof Short || v instanceof Byte
                || v instanceof BigDecimal || v instanceof BigInteger) {
            return true;
        }
        if (depth >= MAX_DEPTH) return false;
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (!isPersistable(o, depth + 1)) return false;
            }
            return true;
        }
        if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String) || !isPersistable(e.getValue(), depth + 1)) return false;
            }
            return true;
        }
        return false;
    }
}
