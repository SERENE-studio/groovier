package com.bluesky.groovier.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局变量维护者:跨脚本共享、reload 保留的内存状态存储。
 * 脚本绑定名 globals(实为 {@link GlobalView} 收窄视图,脚本触不到本类的管理面)。
 * set/remove 时触发变化回调:onChange(ScriptManager 启停检测,单槽位)+ 额外监听器(持久化等)。
 *
 * <p>Java 侧(mod 代码)直接用 {@code GlobalManager.INSTANCE} 读写;
 * 脚本侧只能拿到 GlobalView。setOnChange 仅供 Groovier 内部(sandbox 包)注入,
 * 其他 Java 代码不要调用——覆盖会废掉脚本动态启停。
 */
public class GlobalManager {

    public static final GlobalManager INSTANCE = new GlobalManager();

    private final Map<String, Object> globals = new ConcurrentHashMap<>();

    private volatile Runnable onChange;

    private final List<Runnable> extraChangeListeners = new CopyOnWriteArrayList<>();

    private GlobalManager() {}

    public Object get(String key) {
        return globals.get(key);
    }

    public boolean contains(String key) {
        return globals.containsKey(key);
    }

    public void set(String key, Object value) {
        Object old = globals.put(key, value);
        if (!Objects.equals(old, value)) fireChange();
    }

    public void remove(String key) {
        Object old = globals.remove(key);
        if (old != null) fireChange();
    }

    /** 底层 Map 的只读视图,修改请用 set/remove(否则绕过 onChange 回调)。 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(globals);
    }

    /** 由 ScriptManager 注入(内部用):globals 变化时检测脚本启用/禁用状态。单槽位,勿在他处调用。 */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** 注册额外变更监听(持久化等内部设施用;脚本经 GlobalView 触不到)。 */
    public void addChangeListener(Runnable listener) {
        extraChangeListeners.add(listener);
    }

    /** 启动期静默恢复持久化快照:不触发任何变更监听(避免逐键触发启停扫描)。 */
    void restoreSilently(Map<String, Object> values) {
        globals.putAll(values);
    }

    private void fireChange() {
        // 逐监听器隔离:任一抛错不短路其余(尤其不能让 primary 挂掉 markDirty,造成变更漏落盘)
        Runnable primary = onChange;
        if (primary != null) {
            try {
                primary.run();
            } catch (Throwable t) {
                GroovyLog.INSTANCE.error("globals onChange listener failed: {}", t.toString());
            }
        }
        for (Runnable r : extraChangeListeners) {
            try {
                r.run();
            } catch (Throwable t) {
                GroovyLog.INSTANCE.error("globals change listener failed: {}", t.toString());
            }
        }
    }
}
