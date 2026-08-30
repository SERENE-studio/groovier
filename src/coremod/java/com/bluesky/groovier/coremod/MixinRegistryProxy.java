package com.bluesky.groovier.coremod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 反射摘除 mixin 注册(类级作废核心)。
 *
 * 链路(sponge-mixin 0.15.2,字段名经反编译确认):
 *   MixinEnvironment.getCurrentEnvironment().getActiveTransformer()  -> MixinTransformer
 *     .processor  (MixinProcessor, package-private 字段)
 *       .configs / .pendingConfigs  (List&lt;MixinConfig&gt;)
 *         .mixinMapping  (Map&lt;String target, List&lt;MixinInfo&gt;&gt;)
 *           -> remove(target)                 = 类级作废
 *           -> 按 MixinInfo 逐项 iterator.remove() = 手术级作废(保留其余 mixin)
 *
 * FML 以 newOpenModule/newAutomaticModule 构建层内所有 jar,深层反射可行。
 * 任何一步失败都 fail-safe:仅告警并返回 null,类照常加载(mixin 照常应用)。
 */
final class MixinRegistryProxy {

    /** 一次摘除的结果:removed = mixin类名->config;kept = 手术模式下未被摘除的注册 */
    record InvalidatedMixins(Map<String, String> removed, Map<String, String> kept) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.MixinRegistryProxy");
    private static final Object LOCK = new Object();

    /** 摘除通道可用性(null=未探测, true/false=探测结果) */
    private static volatile Boolean channelAvailable;
    private static Field processorField;
    private static Field configsField;
    private static Field pendingConfigsField;
    private static Field mixinMappingField;
    private static Method mixinInfoClassName;
    private static Method configGetName;

    private MixinRegistryProxy() {}

    /** 提前探测通道并缓存反射句柄;失败则整个作废通道降级为不可用 */
    static void warmUp() {
        synchronized (LOCK) {
            try {
                // 严禁调用 MixinEnvironment 的静态方法(getCurrentEnvironment 等):
                // onLoad 早于 mixin bootstrap,提前触发会执行 MixinEnvironment.init(PREINIT)
                // 抢占 currentPhase,令 bootstrap 期 init() 跳过 wire(phaseConsumer),
                // onStartup 时 NPE 致启动崩溃(实机已验证)。此处只读字段声明。
                Class<?> transformerClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
                processorField = declaredField(transformerClass, "processor");
                if (processorField == null) {
                    channelAvailable = false;
                    return;
                }
                Class<?> processorClass = processorField.getType();
                configsField = declaredField(processorClass, "configs");
                pendingConfigsField = declaredField(processorClass, "pendingConfigs");
                if (configsField == null || pendingConfigsField == null) {
                    channelAvailable = false;
                    return;
                }
                Class<?> configClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig");
                mixinMappingField = declaredField(configClass, "mixinMapping");
                configGetName = configClass.getMethod("getName");
                quietSetAccessible(configGetName);
                Class<?> mixinInfoClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo");
                mixinInfoClassName = mixinInfoClass.getMethod("getClassName");
                // MixinInfo 是 package-private 类,跨模块 invoke 必须 setAccessible,否则
                // IllegalAccessException 退化为 toString() 导致手术规格永远不匹配
                quietSetAccessible(mixinInfoClassName);
                channelAvailable = true;
                LOGGER.info("Groovier mixin invalidation channel ready");
            } catch (ReflectiveOperationException | LinkageError e) {
                channelAvailable = false;
                LOGGER.warn("Groovier mixin invalidation channel unavailable (mixin internals not found): {}", e.toString());
            }
        }
    }

    /**
     * 摘除指向 targetClassName(点分)的 mixin。
     *
     * @param mixinSpecs null = 全摘(类级);非空 = 手术模式,仅移除命中规格(mixin 类名精确或 prefix.*)的注册
     * @return 摘除结果;摘除失败返回 null;无命中返回空 removed
     */
    static InvalidatedMixins invalidateTarget(String targetClassName, List<String> mixinSpecs) {
        Boolean available = channelAvailable;
        if (available == null) {
            warmUp();
            available = channelAvailable;
        }
        if (!Boolean.TRUE.equals(available)) {
            return null;
        }
        synchronized (LOCK) {
            try {
                Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
                Object env = envClass.getMethod("getCurrentEnvironment").invoke(null);
                Object active = envClass.getMethod("getActiveTransformer").invoke(env);
                if (active == null) {
                    LOGGER.warn("Mixin transformer not yet active; cannot invalidate {}", targetClassName);
                    return null;
                }
                Object processor = processorField.get(active);
                if (processor == null) {
                    LOGGER.warn("Mixin processor not initialized; cannot invalidate {}", targetClassName);
                    return null;
                }
                // 摘除 = 写 mixin 内部集合(mixinMapping,普通 HashMap),必须与 mixin 自身
                // 的并发路径互斥。mixin 0.15.2 的防护是 MixinProcessor#applyMixins 为
                // synchronized 实例方法(内部全部 mixinMapping 读写、select/prepareConfigs
                // 均在该监视器内),故这里以同一 processor 实例为监视器即可真互斥。
                // 注意:MixinProcessor.lock 是 org.spongepowered.asm.util.ReEntranceLock
                // ——仅是无同步的递归深度计数器(push/pop 不阻塞、非线程安全),不能当
                // 互斥锁用;跨线程 push 会瞬时抬高全局 depth 并永久置位 semaphore,
                // 可能触发 mixin 的 ReEntrantTransformerError 恐慌路径。
                synchronized (processor) {
                    Map<String, String> removed = new LinkedHashMap<>();
                    Map<String, String> kept = new LinkedHashMap<>();
                    for (Object config : collectConfigs(processor)) {
                        Object mappingObj = mixinMappingField.get(config);
                        if (!(mappingObj instanceof Map<?, ?> mapping)) {
                            continue;
                        }
                        String configName = safeConfigName(config);
                        if (mixinSpecs == null) {
                            // 类级:整键移除
                            Object mixins = mapping.remove(targetClassName);
                            if (mixins instanceof Collection<?> list) {
                                for (Object info : list) {
                                    removed.put(safeMixinClassName(info), configName);
                                }
                            }
                        } else if (mixinSpecs.isEmpty()) {
                            continue;
                        } else {
                            // 手术级:按 MixinInfo 逐项移除,value 为 List(mixin 内部构造,可变)
                            Object mixins = mapping.get(targetClassName);
                            if (!(mixins instanceof List<?> list)) {
                                continue;
                            }
                            for (Iterator<?> it = list.iterator(); it.hasNext(); ) {
                                String mixinClass = safeMixinClassName(it.next());
                                if (matchesAnySpec(mixinClass, mixinSpecs)) {
                                    it.remove();
                                    removed.put(mixinClass, configName);
                                } else {
                                    kept.put(mixinClass, configName);
                                }
                            }
                            if (list.isEmpty()) {
                                mapping.remove(targetClassName);
                            }
                        }
                    }
                    LOGGER.debug("Mixin invalidation for {}: removed {} kept {} (spec mode: {})",
                            targetClassName, removed.size(), kept.size(), mixinSpecs == null ? "class-level" : mixinSpecs.size());
                    return new InvalidatedMixins(removed, kept);
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                LOGGER.error("Mixin invalidation failed for {} (fail-safe: mixins will apply)", targetClassName, e);
                return null;
            }
        }
    }

    /**
     * 返回当前活跃的 MixinProcessor 实例(供 coprocessor 注入复用反射链)。
     *
     * @return processor 实例;不可用或未激活返回 null
     */
    static Object activeProcessor() {
        Boolean available = channelAvailable;
        if (available == null) {
            warmUp();
            available = channelAvailable;
        }
        if (!Boolean.TRUE.equals(available) || processorField == null) {
            return null;
        }
        try {
            Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            Object env = envClass.getMethod("getCurrentEnvironment").invoke(null);
            Object active = envClass.getMethod("getActiveTransformer").invoke(env);
            return active == null ? null : processorField.get(active);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static boolean matchesAnySpec(String mixinClassName, List<String> specs) {
        for (String spec : specs) {
            if (MixinBlacklist.matchesSpec(mixinClassName, spec)) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> collectConfigs(Object processor)
            throws IllegalArgumentException, IllegalAccessException {
        List<Object> all = new ArrayList<>();
        Object configs = configsField.get(processor);
        Object pending = pendingConfigsField.get(processor);
        if (configs instanceof Collection<?> c) {
            all.addAll(c);
        }
        if (pending instanceof Collection<?> c) {
            all.addAll(c);
        }
        return all;
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Field {}.{} not found: {}", owner.getName(), name, e.toString());
            return null;
        }
    }

    /** setAccessible 失败仅告警不抛出(JPMS 拒开包时退化为 fallback 行为) */
    private static void quietSetAccessible(java.lang.reflect.Executable executable) {
        try {
            executable.setAccessible(true);
        } catch (RuntimeException e) {
            LOGGER.warn("setAccessible failed on {}: {}", executable, e.toString());
        }
    }

    private static String safeMixinClassName(Object info) {
        try {
            return (String) mixinInfoClassName.invoke(info);
        } catch (ReflectiveOperationException e) {
            return String.valueOf(info);
        }
    }

    private static String safeConfigName(Object config) {
        try {
            return (String) configGetName.invoke(config);
        } catch (ReflectiveOperationException e) {
            return String.valueOf(config);
        }
    }
}
