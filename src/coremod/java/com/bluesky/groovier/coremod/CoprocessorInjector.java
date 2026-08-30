package com.bluesky.groovier.coremod;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiFunction;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MixinCoprocessor 注入器(残局字节码同步回调通道)。
 *
 * 障碍(0.15.2 字节码确认):MixinCoprocessor 是 abstract class 且
 * process/postProcess 为 package-private —— 跨包子类无法 override(JLS:仅同包子类可覆盖
 * package-private 方法),且 FML 层内 jar 各为独立 module,同包手写类亦不可靠。
 *
 * 解法:ASM 生成 FQN 位于 mixin 同包的 hook 子类字节码,定义进 MixinCoprocessor 的
 * classloader/runtime package:
 *   1. 首选 MethodHandles.privateLookupIn(MixinCoprocessor.class).defineClass(bytes)
 *      (FML newOpenModule 打开全部包;需要 caller module reads mixin module);
 *   2. 兜底深反射 ClassLoader.defineClass(同 loader + 同包名 → package-private 可覆盖)。
 *
 * hook 经 static Object BRIDGE(BiFunction&lt;String, ClassNode, Boolean&gt;)回调 PostWatch,
 * 字节码只引用 JDK 与 mixin 自身类型,不产生跨 module 的直接类引用。
 * 注入点:MixinProcessor.coprocessors(MixinCoprocessors extends ArrayList)→ add。
 *
 * fail-safe:任何失败永久禁用并告警,不影响类加载;processor 未就绪则下次类加载重试。
 */
final class CoprocessorInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.CoprocessorInjector");
    private static final Object LOCK = new Object();
    private static final String HOOK_NAME = "org.spongepowered.asm.mixin.transformer.GroovierCoprocessorHook";

    /** null = 未注入(可重试);true = 已注入;false = 永久失败 */
    private static volatile Boolean state;

    private CoprocessorInjector() {}

    /** 幂等注入;失败(not-ready 除外)后不再尝试 */
    static void ensureInjected() {
        if (state != null) {
            return;
        }
        synchronized (LOCK) {
            if (state != null) {
                return;
            }
            try {
                if (inject()) {
                    state = true;
                    LOGGER.info("Groovier post-mixin coprocessor injected (residual bytecode channel live)");
                }
                // false = processor 未就绪,保留 state=null,下次类加载重试
            } catch (Throwable t) {
                state = false;
                LOGGER.warn("Groovier coprocessor injection failed permanently "
                        + "(post-mixin watching disabled, classes load normally): {}", t.toString());
            }
        }
    }

    /** @return true = 注入成功;false = processor 未就绪(重试);异常 = 永久失败 */
    private static boolean inject() throws Exception {
        Object processor = MixinRegistryProxy.activeProcessor();
        if (processor == null) {
            return false;
        }
        Class<?> coprocessorClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinCoprocessor");
        byte[] bytes = buildHook();
        Class<?> hookClass = defineHookClass(coprocessorClass, bytes);
        Field bridge = hookClass.getDeclaredField("BRIDGE");
        bridge.setAccessible(true);
        bridge.set(null, (BiFunction<String, ClassNode, Boolean>) CoprocessorInjector::dispatchPost);
        Constructor<?> ctor = hookClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object hook = ctor.newInstance();
        Field coprocessorsField = processor.getClass().getDeclaredField("coprocessors");
        coprocessorsField.setAccessible(true);
        Object coprocessors = coprocessorsField.get(processor);
        @SuppressWarnings("unchecked")
        java.util.Collection<Object> list = (java.util.Collection<Object>) coprocessors;
        list.add(hook);
        return true;
    }

    /**
     * coprocessor 回调分发:PostWatch(pre/post 对照取证)→ ReferStore(6.5 残局捕获)
     * → OverrideStore(6.4 整类替换)。顺序有意义:refer/post 取证的必须是替换前的
     * 残局字节(覆盖类的契约基准),OverrideStore 就地改写 node 后取证即自参照;
     * override 返回 true = ClassNode 已被整体替换。
     * fail-safe:任何 consumer 漏网异常在此吞掉并返回 false 放行原字节,
     * 不得沿 hook 字节码传播导致目标类 define 失败。
     */
    private static boolean dispatchPost(String className, ClassNode node) {
        try {
            PostWatch.inspectPost(className, node);
            ReferStore.inspectPost(className, node);
            return OverrideStore.inspectPost(className, node);
        } catch (Throwable t) {
            LOGGER.warn("Groovier coprocessor dispatch failed on {}, loading original class: {}",
                    className, t.toString());
            return false;
        }
    }

    private static Class<?> defineHookClass(Class<?> coprocessorClass, byte[] bytes) throws Exception {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(coprocessorClass, MethodHandles.lookup());
            return lookup.defineClass(bytes);
        } catch (ReflectiveOperationException primaryFailure) {
            LOGGER.debug("privateLookupIn route unavailable, falling back to ClassLoader.defineClass: {}",
                    primaryFailure.toString());
            ClassLoader loader = coprocessorClass.getClassLoader();
            Method define = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            define.setAccessible(true);
            return (Class<?>) define.invoke(loader, HOOK_NAME, bytes, 0, bytes.length);
        }
    }

    /**
     * 生成 hook 字节码(等价源码):
     * public class GroovierCoprocessorHook extends MixinCoprocessor {
     *     public static Object BRIDGE;
     *     public GroovierCoprocessorHook() { super(); }
     *     boolean postProcess(String name, ClassNode node) {
     *         Object b = BRIDGE;
     *         if (b == null) return false;
     *         return Boolean.TRUE.equals(((BiFunction) b).apply(name, node));
     *     }
     * }
     */
    private static byte[] buildHook() throws Exception {
        String pkg = "org/spongepowered/asm/mixin/transformer";
        String superName = pkg + "/MixinCoprocessor";
        String hookName = pkg + "/GroovierCoprocessorHook";

        ClassNode node = new ClassNode();
        node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, hookName, null, superName, null);
        node.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BRIDGE", "Ljava/lang/Object;", null, null).visitEnd();

        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        // ClassWriter(0) 不计算帧,手工 maxs 缺失会导致 ClassFormatError "Arguments can't fit into locals"
        ctor.visitMaxs(1, 1);
        node.methods.add(ctor);

        // package-private 覆盖:access 不含 ACC_PUBLIC
        // 栈序:预压 Boolean.TRUE 作为 receiver,apply 结果作参数 → TRUE.equals(result)
        MethodNode pp = new MethodNode(0, "postProcess",
                "(Ljava/lang/String;Lorg/objectweb/asm/tree/ClassNode;)Z", null, null);
        Label apply = new Label();
        pp.visitFieldInsn(Opcodes.GETSTATIC, hookName, "BRIDGE", "Ljava/lang/Object;");
        pp.visitVarInsn(Opcodes.ASTORE, 3);
        pp.visitVarInsn(Opcodes.ALOAD, 3);
        pp.visitJumpInsn(Opcodes.IFNONNULL, apply);
        pp.visitInsn(Opcodes.ICONST_0);
        pp.visitInsn(Opcodes.IRETURN);
        pp.visitLabel(apply);
        // apply 处栈为空:locals = [this, name, node, bridge]
        pp.visitFrame(Opcodes.F_FULL, 4,
                new Object[] { hookName, "java/lang/String", "org/objectweb/asm/tree/ClassNode", "java/lang/Object" },
                0, new Object[] {});
        pp.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        pp.visitVarInsn(Opcodes.ALOAD, 3);
        pp.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
        pp.visitVarInsn(Opcodes.ALOAD, 1);
        pp.visitVarInsn(Opcodes.ALOAD, 2);
        pp.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        pp.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "equals",
                "(Ljava/lang/Object;)Z", false);
        pp.visitInsn(Opcodes.IRETURN);
        // apply 段峰值栈 4:TRUE + BiFunction + name + node
        pp.visitMaxs(4, 4);
        node.methods.add(pp);

        // 手写帧(唯一分支点),不用 COMPUTE_FRAMES:避免生成期 ClassWriter 反向加载类层级
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
