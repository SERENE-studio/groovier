package com.bluesky.groovier.coremod;

import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.ClassNode;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;

/**
 * 整类覆盖目标的 coprocessor 注入触发器(6.4)。
 *
 * 与 ReferTransformer 同构:override 目标可能独立于 postwatch/refer 存在
 * (config/groovier-override.txt),由本 transformer 在首个目标类的 pre-mixin
 * transform 时机调用 ensureInjected(),保证残局回调(coprocessor)就位。
 * 不捕获、不改写,原样放行;真正的替换在 OverrideStore.inspectPost(mixin 后)。
 */
final class OverrideTransformer implements ITransformer<ClassNode> {

    private final Set<String> targets;

    OverrideTransformer(Set<String> targets) {
        this.targets = targets;
    }

    boolean isEmpty() {
        return targets.isEmpty();
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        CoprocessorInjector.ensureInjected();
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
