package com.bluesky.groovier.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;

import java.util.Map;

/**
 * Groovier 触发给 KubeJS 脚本的事件负载:事件名 + 数据 Map。
 * KubeJS 脚本侧通过 GroovierEvents.fire("name", event -> ...) 监听,event.name / event.data 访问负载。
 */
public class GroovierKubeEvent implements KubeEvent {

    private final String name;
    private final Map<String, Object> data;

    public GroovierKubeEvent(String name, Map<String, Object> data) {
        this.name = name;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
