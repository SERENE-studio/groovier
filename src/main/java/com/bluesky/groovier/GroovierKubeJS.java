package com.bluesky.groovier;

import net.neoforged.fml.ModList;

import java.util.Map;

/**
 * Groovier → KubeJS 联动的运行时检测与路由(主包,不引用 KubeJS 类)。
 * KubeJS 为可选依赖:未加载时 Events.fire 安全 no-op。
 */
public final class GroovierKubeJS {

    private GroovierKubeJS() {}

    /** KubeJS 是否加载(运行时检测)。 */
    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded("kubejs");
    }

    /** 触发 KubeJS 事件。KubeJS 不存在时静默忽略(懒加载隔离,避免 NoClassDefFoundError)。 */
    public static void fire(String name, Map<String, Object> data) {
        if (!isLoaded()) return;
        // 仅当 KubeJS 存在时才加载 kubejs 包下的类
        com.bluesky.groovier.kubejs.GroovierKubeJSApi.fire(name, data);
    }
}
