package com.bluesky.groovier.coremod;

import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.ClassNode;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;

/**
 * 残局观测类的 pre 捕获 + coprocessor 注入触发器。
 *
 * transform(管线第 2 阶段,mixin 之前):
 *   1. ensureInjected():首个观测类加载时注入 MixinCoprocessor(注入后 postProcess
 *      会在 mixin 应用后、define 前回调,落盘残局);
 *   2. watchPre():落盘观测类原身,与 coprocessor 残局产物成对(pre vs post 对照)。
 */
final class PostWatchTransformer implements ITransformer<ClassNode> {

    private final Set<String> targets;

    PostWatchTransformer(Set<String> targets) {
        this.targets = targets;
    }

    boolean isEmpty() {
        return targets.isEmpty();
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName();
        CoprocessorInjector.ensureInjected();
        try {
            PostWatch.watchPre(className, input);
        } catch (RuntimeException e) {
            // 捕获失败不影响类加载
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public Set<ITransformer.Target<ClassNode>> targets() {
        return targets.stream()
                .map(ITransformer.Target::<ClassNode>targetClass)
                .collect(Collectors.toUnmodifiableSet());
    }
}
