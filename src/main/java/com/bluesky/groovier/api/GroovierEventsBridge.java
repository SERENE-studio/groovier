package com.bluesky.groovier.api;

import com.bluesky.groovier.GroovierKubeJS;

import java.util.Map;

/**
 * 脚本绑定 Events:groovier 脚本向 KubeJS 触发事件(联动通道)。
 * Events.fire("事件名", [键: 值]) → KubeJS 脚本 GroovierEvents.fire("事件名", event -> ...) 响应。
 * KubeJS 未加载时安全 no-op(记录日志)。
 */
public class GroovierEventsBridge {

    public static final GroovierEventsBridge INSTANCE = new GroovierEventsBridge();

    public void fire(String name, Map<String, Object> data) {
        if (name == null || name.isEmpty()) {
            GroovyLog.INSTANCE.error("Events.fire requires a non-empty event name.");
            return;
        }
        if (!GroovierKubeJS.isLoaded()) {
            GroovyLog.INSTANCE.warn("KubeJS is not loaded, ignoring Events.fire('{}').", name);
            return;
        }
        GroovierKubeJS.fire(name, data == null ? Map.of() : data);
    }
}
