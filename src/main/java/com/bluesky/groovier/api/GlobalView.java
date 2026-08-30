package com.bluesky.groovier.api;

import java.util.Map;

/**
 * 脚本侧 globals 绑定的收窄视图:仅暴露 get/contains/set/remove/asMap,
 * 不暴露 {@link GlobalManager#setOnChange} 等内部管理面。
 *
 * <p>背景:沙箱对 GlobalManager 类名做了编译期 ban,但绑定对象是同一实例,
 * Groovy 经实例分发仍可调用任意 public 方法(setOnChange 是单槽位,被脚本覆盖
 * 会永久废掉脚本动态启停检测)。因此绑定改为本视图——脚本物理上拿不到
 * GlobalManager 实例,Java 侧(mod 代码)不受影响,仍用 {@code GlobalManager.INSTANCE}。
 */
public final class GlobalView {

    private final GlobalManager gm;

    public GlobalView(GlobalManager gm) {
        this.gm = gm;
    }

    public Object get(String key) {
        return gm.get(key);
    }

    public boolean contains(String key) {
        return gm.contains(key);
    }

    public void set(String key, Object value) {
        gm.set(key, value);
    }

    public void remove(String key) {
        gm.remove(key);
    }

    /** 底层 Map 的只读视图(修改请用 set/remove,否则绕过变更回调)。 */
    public Map<String, Object> asMap() {
        return gm.asMap();
    }
}
