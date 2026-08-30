package com.bluesky.groovier.coremod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

/**
 * M2 双捕获 / mixin 类级作废通道(源码实证结论,勿回退到 SPEC §10.1 原"post 回写"方案):
 *
 * - mixin 在 modlauncher 11 中以 Launch Plugin(AFTER 阶段)生效,fml 侧 ITransformer
 *   全部先于 mixin 执行(优先级排序不存在,见 TransformList 无排序逻辑);
 * - 因此"原身"在 ITransformer 捕获,"作废"不是在 mixin 后回写字节码,而是
 *   在 mixin 应用前反射摘除 MixinConfig.mixinMapping 中指向黑名单类的条目,
 *   同一次类加载的 AFTER 阶段 mixin 查空,类以原身加载;
 * - 本 service 声明于 META-INF/services,ModDirTransformerDiscoverer 将本 jar
 *   拉入 SERVICE 层(早于一切 mod 代码);jar 同时保持 mod 身份(FML 4.0.42
 *   的 allExcluded() 无调用方,不会被排除出 mods 扫描)。
 */
public class GroovierTransformationService implements ITransformationService {

    public static final String NAME = "groovier";

    private static final Logger LOGGER = LoggerFactory.getLogger(GroovierTransformationService.class);

    private volatile MixinInvalidationTransformer transformer;
    private volatile PostWatchTransformer watchTransformer;
    private volatile ReferTransformer referTransformer;
    private volatile PinningTransformer pinTransformer;
    private volatile OverrideTransformer overrideTransformer;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        // SERVICE 层最早入口:此时 FMLPaths 已由 ModDirTransformerDiscoverer.candidates 初始化
        MixinBlacklist.load(environment);
        PostWatch.load(environment);
        ReferStore.load(environment);
        OverrideStore.load(environment);
        SurgeryStore.load(environment);
        PinStore.load(environment);
        InvalidationStore.init(environment);
        MixinRegistryProxy.warmUp();
    }

    @Override
    public void initialize(IEnvironment environment) {
        // noop:配置已在 onLoad 加载
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        List<ITransformer<?>> out = new ArrayList<>();
        MixinInvalidationTransformer t = transformerOf();
        if (!t.isEmpty()) {
            out.add(t);
        }
        PostWatchTransformer w = watchTransformerOf();
        if (!w.isEmpty()) {
            out.add(w);
        }
        ReferTransformer r = referTransformerOf();
        if (!r.isEmpty()) {
            out.add(r);
        }
        PinningTransformer p = pinTransformerOf();
        if (!p.isEmpty()) {
            out.add(p);
        }
        OverrideTransformer o = overrideTransformerOf();
        if (!o.isEmpty()) {
            out.add(o);
        }
        return out;
    }

    /**
     * override 触发器:配置存在但前缀展开后无具体目标时(dev 环境 mods 目录无类 jar),
     * 回退锚定 MinecraftServer(必加载、加载早),保证 coprocessor 注入不缺席。
     */
    private OverrideTransformer overrideTransformerOf() {
        OverrideTransformer t = overrideTransformer;
        if (t == null) {
            synchronized (this) {
                if (overrideTransformer == null) {
                    Set<String> targets = new LinkedHashSet<>(OverrideStore.resolvedTriggerTargets());
                    if (targets.isEmpty() && OverrideStore.hasRules()) {
                        LOGGER.warn("Groovier override rules present but no concrete target resolved "
                                + "(prefix rules need mod jars), falling back to MinecraftServer anchor");
                        targets.add("net.minecraft.server.MinecraftServer");
                    }
                    overrideTransformer = new OverrideTransformer(targets);
                }
            }
        }
        return overrideTransformer;
    }

    private MixinInvalidationTransformer transformerOf() {
        MixinInvalidationTransformer t = transformer;
        if (t == null) {
            synchronized (this) {
                if (transformer == null) {
                    // 黑名单 ∪ 手术包 targets(手术包优先于黑名单,transform 内分流)
                    Set<String> targets = new LinkedHashSet<>(MixinBlacklist.resolvedTargets());
                    targets.addAll(SurgeryStore.resolvedTargets());
                    transformer = new MixinInvalidationTransformer(targets);
                }
                t = transformer;
            }
        }
        return t;
    }

    private PostWatchTransformer watchTransformerOf() {
        PostWatchTransformer t = watchTransformer;
        if (t == null) {
            synchronized (this) {
                if (watchTransformer == null) {
                    watchTransformer = new PostWatchTransformer(PostWatch.resolvedTargets());
                }
                t = watchTransformer;
            }
        }
        return t;
    }

    private ReferTransformer referTransformerOf() {
        ReferTransformer t = referTransformer;
        if (t == null) {
            synchronized (this) {
                if (referTransformer == null) {
                    referTransformer = new ReferTransformer(ReferStore.resolvedTargets());
                }
                t = referTransformer;
            }
        }
        return t;
    }

    private PinningTransformer pinTransformerOf() {
        PinningTransformer t = pinTransformer;
        if (t == null) {
            synchronized (this) {
                if (pinTransformer == null) {
                    pinTransformer = new PinningTransformer(PinStore.resolvedTargets());
                }
                t = pinTransformer;
            }
        }
        return t;
    }
}
