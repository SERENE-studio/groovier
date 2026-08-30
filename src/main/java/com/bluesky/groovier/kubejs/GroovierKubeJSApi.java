package com.bluesky.groovier.kubejs;

import dev.latvian.mods.kubejs.script.ScriptType;

import java.util.Map;

/**
 * Groovier → KubeJS 触发门面。仅在 KubeJS 已加载时被调用(由主包 GroovierKubeJS 检测后路由)。
 * 引用 KubeJS 类,因此必须懒加载:无 KubeJS 环境不加载本类。
 */
public final class GroovierKubeJSApi {

    private GroovierKubeJSApi() {}

    /** 触发 GroovierEvents.fire 事件,按事件名分发,负载为 name + data。 */
    public static void fire(String name, Map<String, Object> data) {
        GroovierKubeJSPlugin.FIRE.post(ScriptType.SERVER, name, new GroovierKubeEvent(name, data));
    }
}
