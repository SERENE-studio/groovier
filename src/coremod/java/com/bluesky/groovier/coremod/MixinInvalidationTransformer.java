package com.bluesky.groovier.coremod;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;

/**
 * 黑名单类的 pre-mixin 捕获 + mixin 注册摘除;手术包应用入口(SurgeryStore 优先,黑名单回退)。
 *
 * 调用时机:管线第 2 阶段(ITransformer),先于 Launch Plugin AFTER 阶段(mixin)。
 * 调用前提:该类是被 blacklists 声明(或包前缀展开)或手术包 target 声明的目标类。
 */
final class MixinInvalidationTransformer implements ITransformer<ClassNode> {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.MixinInvalidationTransformer");

    private final Set<String> targets;

    MixinInvalidationTransformer(Set<String> targets) {
        this.targets = targets;
    }

    boolean isEmpty() {
        return targets.isEmpty();
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName();
        // 1. 深拷贝缓存"原身"(input 会在后续 mixin AFTER 阶段被原地修改;同一序列化复用于锚定校验)
        byte[] preBytes;
        try {
            preBytes = serialize(input);
            InvalidationStore.preCapture(className, preBytes);
        } catch (RuntimeException e) {
            InvalidationStore.logCaptureFailure(className, e);
            preBytes = null;
        }
        try {
            // 2. 手术包优先:命中 → 锚定校验 → 按 mode 摘除 → 返回补丁字节码
            SurgeryStore.SurgeryPlan plan = SurgeryStore.forTarget(className);
            if (plan != null) {
                return applySurgery(plan, className, input, preBytes);
            }
            // 3. 黑名单规则:类级全摘(null)或手术级仅摘命中注册;失败 fail-safe(null = 通道不可用)
            List<String> specs = MixinBlacklist.mixinSpecsFor(className);
            MixinRegistryProxy.InvalidatedMixins removed = MixinRegistryProxy.invalidateTarget(className, specs);
            InvalidationStore.recordInvalidation(className, removed);
        } catch (RuntimeException e) {
            InvalidationStore.recordInvalidationFailure(className, e);
        }
        return input;
    }

    /**
     * 手术包应用:锚定失配 → 停用(报告 + 回退黑名单流程);匹配 → 按 mode 摘除 + 输出补丁。
     * 补丁字节码解析失败 fail-safe:返回原身(mixin 摘除已生效,类可正常加载)。
     */
    private ClassNode applySurgery(SurgeryStore.SurgeryPlan plan, String className, ClassNode input, byte[] preBytes) {
        if (preBytes == null) {
            // pre 序列化失败 → sha256 锚定无法验证:按"失配=停用"处理,回退黑名单流程,
            // 不得跳过校验直接应用手术包
            InvalidationStore.recordSurgery(plan.name(), plan.target(),
                    "disabled (pre capture unavailable, sha anchor unverifiable)");
            LOGGER.error("Groovier surgery pack {} disabled for {} (pre capture unavailable, sha anchor unverifiable)",
                    plan.name(), className);
            applyBlacklist(className);
            return input;
        }
        String actual = SurgeryStore.sha256Hex(preBytes);
        if (!actual.equals(plan.preSha256())) {
            InvalidationStore.recordSurgery(plan.name(), plan.target(),
                    "stale (pre sha mismatch: recorded " + plan.preSha256().substring(0, 8) + "..., actual "
                            + actual.substring(0, 8) + "...)");
            LOGGER.error("Groovier surgery pack {} stale for {} (pre sha mismatch), disabled for this launch",
                    plan.name(), className);
            applyBlacklist(className);
            return input;
        }
        List<String> specs = plan.exclusive() ? null : plan.dropSpecs();
        MixinRegistryProxy.InvalidatedMixins removed = MixinRegistryProxy.invalidateTarget(className, specs);
        InvalidationStore.recordInvalidation(className, removed);
        int removedCount = removed == null ? 0 : removed.removed().size();
        InvalidationStore.recordSurgery(plan.name(), plan.target(),
                (plan.exclusive() ? "applied (exclusive)" : "applied (patch_with_mixins)")
                        + ", " + removedCount + " mixin(s) removed");
        try {
            ClassNode patched = new ClassNode();
            new ClassReader(plan.patchBytes()).accept(patched, 0);
            LOGGER.info("Groovier surgery pack {} applied to {} ({} mode)", plan.name(), className,
                    plan.exclusive() ? "exclusive" : "patch_with_mixins");
            return patched;
        } catch (RuntimeException e) {
            LOGGER.error("Groovier surgery pack {} patch unreadable, falling back to original class", plan.name(), e);
            InvalidationStore.recordSurgery(plan.name(), plan.target(), "error (patch unreadable: " + e + ")");
            return input;
        }
    }

    private void applyBlacklist(String className) {
        List<String> specs = MixinBlacklist.mixinSpecsFor(className);
        MixinRegistryProxy.InvalidatedMixins removed = MixinRegistryProxy.invalidateTarget(className, specs);
        InvalidationStore.recordInvalidation(className, removed);
    }

    private static byte[] serialize(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
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
