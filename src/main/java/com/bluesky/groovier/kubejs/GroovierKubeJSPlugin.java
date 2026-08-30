package com.bluesky.groovier.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

/**
 * Groovier 的 KubeJS 插件:注册事件组 GroovierEvents(KubeJS 脚本可监听)与绑定。
 * 事件组:GroovierEvents.fire("事件名", event => ...) —— 按事件名(String extraId)分发。
 * 由 groovier 脚本的 Events.fire(name, data) 触发(见 GroovierKubeJSApi)。
 */
public class GroovierKubeJSPlugin implements KubeJSPlugin {

    public static final EventGroup GROUP = EventGroup.of("GroovierEvents");
    public static final TargetedEventHandler<String> FIRE = GROUP.server("fire", () -> GroovierKubeEvent.class)
            .supportsTarget(EventTargetType.STRING);

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(GROUP);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("GroovierEvents", GROUP);
    }
}
