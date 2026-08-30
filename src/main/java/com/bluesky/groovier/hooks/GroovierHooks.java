package com.bluesky.groovier.hooks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方法钉子运行时查询点宿主(SPEC 6.3)。
 *
 * <p>被钉方法(核心侧重命名包装生成)在方法入口调用 {@link #enter},
 * 原方法实际执行后调用 {@link #exit}。本类被 GAME 层各类按名引用,必须零依赖
 * (不 import MC/Groovy),尽早可加载;回调经 {@link PinCallback} 适配,由
 * GAME 层 PinsApi 注册(脚本 override/on)。
 *
 * <p>语义:
 * <ul>
 *   <li>enter 返回 null = 无覆盖,原方法透明执行;返回非 null = 覆盖返回值
 *       (基本类型须装箱;void 方法任意非 null 即"直接返回")。多个回调按注册序,
 *       首个非 null 生效;回调异常记日志视为未覆盖(不影响原方法)。</li>
 *   <li>exit 仅在原方法实际执行时触发(override 命中则跳过),仅观察,不修改返回值。</li>
 * </ul>
 *
 * <p>热路径开销:无覆盖时 = 一次 ConcurrentHashMap.get(key) 命中空 + null 返回。
 */
public final class GroovierHooks {

    /** 钉子回调:thiz = 接收者(static 钉为 null),args = 装箱参数数组,result = 装箱返回值(exit 专用,void 为 null) */
    public interface PinCallback {
        Object invoke(Object thiz, Object[] args, Object result) throws Throwable;
    }

    private record Registered(String script, PinCallback callback) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.hooks.GroovierHooks");

    private static final Map<String, List<Registered>> ENTER = new ConcurrentHashMap<>();
    private static final Map<String, List<Registered>> EXIT = new ConcurrentHashMap<>();

    private GroovierHooks() {}

    /** 查询点:钉子包装的方法入口调用;返回 null 放行,非 null 覆盖返回值(装箱)。 */
    public static Object enter(String key, Object thiz, Object[] args) {
        List<Registered> list = ENTER.get(key);
        if (list == null) {
            return null;
        }
        for (Registered r : list) {
            try {
                Object v = r.callback().invoke(thiz, args, null);
                if (v != null) {
                    return v;
                }
            } catch (Throwable t) {
                LOGGER.error("Pin enter callback failed for {} (script {}), passing through", key, r.script(), t);
            }
        }
        return null;
    }

    /** 查询点:原方法实际执行后调用(override 命中时跳过);仅观察。 */
    public static void exit(String key, Object thiz, Object[] args, Object result) {
        List<Registered> list = EXIT.get(key);
        if (list == null) {
            return;
        }
        for (Registered r : list) {
            try {
                r.callback().invoke(thiz, args, result);
            } catch (Throwable t) {
                LOGGER.error("Pin exit callback failed for {} (script {})", key, r.script(), t);
            }
        }
    }

    // ---- 管理面(GAME 层 PinsApi 调用) ----

    public static void registerEnter(String key, String script, PinCallback callback) {
        ENTER.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new Registered(script, callback));
    }

    public static void registerExit(String key, String script, PinCallback callback) {
        EXIT.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new Registered(script, callback));
    }

    /** 注销指定脚本注册的全部回调(脚本禁用/执行失败回滚)。 */
    public static void unregisterScript(String script) {
        removeByScript(ENTER, script);
        removeByScript(EXIT, script);
    }

    /** 全量注销(reload)。 */
    public static void reset() {
        ENTER.clear();
        EXIT.clear();
    }

    /** key -> enter/exit 回调数(诊断) */
    public static Map<String, int[]> counts() {
        Map<String, int[]> out = new java.util.TreeMap<>();
        ENTER.forEach((k, v) -> out.computeIfAbsent(k, x -> new int[2])[0] = v.size());
        EXIT.forEach((k, v) -> out.computeIfAbsent(k, x -> new int[2])[1] = v.size());
        return out;
    }

    private static void removeByScript(Map<String, List<Registered>> table, String script) {
        for (List<Registered> list : table.values()) {
            list.removeIf(r -> r.script().equals(script));
        }
    }
}
