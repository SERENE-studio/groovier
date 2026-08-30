package com.bluesky.groovier.event;

import com.bluesky.groovier.api.GroovyLog;
import com.bluesky.groovier.sandbox.ScriptManager;
import groovy.lang.Closure;
import groovy.lang.Script;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件管理器:将 Groovy 闭包包装为 NeoForge 监听器,按脚本分组登记。
 * 支持:reset() 全量注销(reload);unregisterScript() 注销单个脚本(脚本禁用)。
 * 闭包参数类型(唯一参数,须为 Event 子类)决定监听的事件类型。
 */
public class GroovierEventManager {

    public static final GroovierEventManager INSTANCE = new GroovierEventManager();

    // 脚本相对路径 -> 该脚本注册的监听器
    private final Map<String, List<Consumer<?>>> listenersByScript = new ConcurrentHashMap<>();

    // 当前正在执行的脚本(ScriptEngine 设置)
    private volatile String currentScript = "";

    public void setCurrentScript(String script) {
        this.currentScript = script == null ? "" : script;
    }

    /** 当前正在执行的脚本相对路径(钉子回调等归属登记用;不在脚本执行期返回空串)。 */
    public String currentScript() {
        return currentScript;
    }

    public void listen(Closure<?> closure) {
        Class<?>[] paramTypes = closure.getParameterTypes();
        if (paramTypes.length != 1) {
            GroovyLog.INSTANCE.error("Event listener closure must have exactly one parameter, got {} parameters.", paramTypes.length);
            return;
        }
        Class<?> eventClass = paramTypes[0];
        if (!Event.class.isAssignableFrom(eventClass)) {
            GroovyLog.INSTANCE.error("Event listener parameter must be an Event subclass, got {}.", eventClass.getName());
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends Event> evt = (Class<? extends Event>) eventClass;
        listen(evt, closure);
    }

    public <T extends Event> void listen(Class<T> eventClass, Closure<?> closure) {
        Consumer<T> consumer = event -> runClosure(closure, event);
        String script = currentScript;
        if (script.isEmpty()) {
            // 事件回调内注册:当前脚本上下文已清空,沿闭包 owner 链反查归属脚本,避免挂到空分组导致注销不到
            script = resolveOwnerScript(closure);
            if (script.isEmpty()) {
                // L10:owner 链反查失败 → 拒绝注册。挂入 "" 分组会泄漏且 unregisterScript 语义不闭合
                GroovyLog.INSTANCE.error("Cannot resolve owning script for listener of {}, listener rejected.", eventClass.getName());
                return;
            }
        }
        NeoForge.EVENT_BUS.addListener(eventClass, consumer);
        listenersByScript.computeIfAbsent(script, k -> new CopyOnWriteArrayList<>()).add(consumer);
        GroovyLog.INSTANCE.info("Registered event listener for {} in script {}", eventClass.getName(), script);
    }

    /** 沿闭包 owner 链上溯找到定义它的脚本实例,反查其相对路径;查不到返回空串。 */
    public String resolveOwnerScript(Closure<?> closure) {
        Object owner = closure.getOwner();
        while (owner != null && !(owner instanceof Script)) {
            if (owner instanceof Closure) {
                owner = ((Closure<?>) owner).getOwner();
            } else {
                break;
            }
        }
        if (owner instanceof Script) {
            return ScriptManager.lookupScriptPath(owner.getClass().getName());
        }
        return "";
    }

    private static void runClosure(Closure<?> closure, Object event) {
        try {
            closure.call(event);
        } catch (Throwable t) {
            GroovyLog.INSTANCE.error("Error in event listener for {}: {}", event.getClass().getName(), t);
        }
    }

    /** 注销指定脚本注册的全部监听器(脚本禁用时调用)。 */
    public void unregisterScript(String script) {
        List<Consumer<?>> list = listenersByScript.remove(script);
        if (list != null) {
            for (Consumer<?> consumer : list) {
                NeoForge.EVENT_BUS.unregister(consumer);
            }
        }
    }

    /** 注销全部已登记的监听器(热重载前调用)。 */
    public void reset() {
        for (List<Consumer<?>> list : listenersByScript.values()) {
            for (Consumer<?> consumer : list) {
                NeoForge.EVENT_BUS.unregister(consumer);
            }
        }
        listenersByScript.clear();
    }

    /** 当前登记的监听器总数(诊断用)。 */
    public int listenerCount() {
        return listenersByScript.values().stream().mapToInt(List::size).sum();
    }
}
