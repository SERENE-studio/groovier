package com.bluesky.groovier.coremod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;

/**
 * 方法钉子注入(SPEC 6.3,原身通路):ITransformer 阶段(mixin 前)对命中方法做
 * <b>重命名包装</b>——原方法改名 {@code groovier$orig$<name>},新建同名方法作为包装,
 * 方法入口生成 {@code GroovierHooks.enter/exit} 查询点;未命中(运行时覆盖表为空)透明放行。
 *
 * <p>为什么不"方法体首行原地注入":原地插入必须 COMPUTE_FRAMES,而 ASM
 * {@code getCommonSuperClass} 在 SERVICE 层无法解析 mod/MC 类型(类尚未加载),
 * 错误栈帧 = VerifyError 风险。包装法只生成 JDK 类型代码,栈帧手写零重算,
 * 原方法体字节零改动(mixin 兼容面最小),语义等价(查询点在方法入口)。
 *
 * <p>不钉:<init>/<clinit>(构造器无法改名委托)、native(JNI 按名绑定会断)、
 * abstract(无体;钉具体实现)、synthetic/bridge(桥接体引用原签名,自动经包装走钩子)。
 *
 * <p>fail-safe:任何异常丢弃改写副本返回原字节,报告标 error,类正常加载。
 */
final class PinningTransformer implements ITransformer<ClassNode> {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.bluesky.groovier.coremod.PinningTransformer");

    /** 运行时查询点宿主(GAME 层主模组包;生成字节码仅按名引用,无编译依赖) */
    static final String HOOKS = "com/bluesky/groovier/hooks/GroovierHooks";
    private static final String ENTER_DESC = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String EXIT_DESC = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String ORIG_PREFIX = "groovier$orig$";

    /** 已包装方法全局记录(className.name+desc):跨 plan 去重,同方法只包装一次(首个钉子包生效) */
    private static final Set<String> WRAPPED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Set<String> targets;

    PinningTransformer(Set<String> targets) {
        this.targets = targets;
    }

    boolean isEmpty() {
        return targets.isEmpty();
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName();
        List<PinStore.PinPlan> plans = PinStore.pinsFor(className);
        if (plans.isEmpty()) {
            return input;
        }
        // 深拷贝改写:失败丢弃副本返回原字节(fail-safe,类正常加载)
        ClassNode copy = new ClassNode();
        input.accept(copy);
        Map<String, String> statuses = new java.util.LinkedHashMap<>();
        try {
            for (PinStore.PinPlan plan : plans) {
                applyPin(copy, className, plan, statuses);
            }
            for (var e : statuses.entrySet()) {
                InvalidationStore.recordPin(e.getKey(), className, e.getValue());
            }
            return copy;
        } catch (RuntimeException e) {
            LOGGER.error("Groovier pin injection failed on {}, falling back to original class", className, e);
            for (PinStore.PinPlan plan : plans) {
                InvalidationStore.recordPin(plan.name(), className,
                        "error (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
            return input;
        }
    }

    /** 对单个钉子计划应用重命名包装;状态记入 statuses(name -> applied(n)/skipped(...)/error(...))。 */
    private void applyPin(ClassNode node, String className, PinStore.PinPlan plan, Map<String, String> statuses) {
        String key = className + "." + plan.method();
        int count = 0;
        int alreadyWrapped = 0;
        for (MethodNode mn : new ArrayList<>(node.methods)) {
            if (!mn.name.equals(plan.method())
                    || (plan.descriptor() != null && !mn.desc.equals(plan.descriptor()))) {
                continue;
            }
            if (isExcluded(mn)) {
                // 本类 wrapper 带 ACC_SYNTHETIC(原方法已被改名):同名同 desc 键已入全局记录,
                // 说明它是先前 plan 的包装 → 当前 plan 属重复声明
                if ((mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                        && !WRAPPED.add(className + "." + mn.name + mn.desc)) {
                    alreadyWrapped++;
                }
                continue;
            }
            // 跨 plan 去重(全局记录):同方法已被前一计划包装则跳过(首个生效)
            if (!WRAPPED.add(className + "." + mn.name + mn.desc)) {
                alreadyWrapped++;
                continue;
            }
            wrapMethod(node, mn, key, plan.method());
            count++;
        }
        if (count > 0) {
            statuses.put(plan.name(), "applied (" + count + " method(s))");
        } else if (alreadyWrapped > 0) {
            statuses.put(plan.name(), "skipped (already wrapped by another pin pack)");
            LOGGER.info("Groovier pin pack '{}' skipped: method '{}' on {} already wrapped by another pin pack",
                    plan.name(), plan.method(), className);
        } else {
            statuses.put(plan.name(), "error (no matching method '" + plan.method() + "')");
            LOGGER.warn("Groovier pin pack '{}' matched no method '{}' on {}", plan.name(), plan.method(), className);
        }
    }

    private static boolean isExcluded(MethodNode mn) {
        return mn.name.startsWith("<")                       // <init>/<clinit>:构造器无法改名委托
                || (mn.access & Opcodes.ACC_NATIVE) != 0     // JNI 按名绑定,改名即断链
                || (mn.access & Opcodes.ACC_ABSTRACT) != 0   // 无方法体
                || (mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
    }

    /**
     * 重命名包装:mn 原地改名为 {@code groovier$orig$<name>},紧随其后插入同名包装方法。
     * 包装体(仅 JDK 类型,唯一手写帧在分支汇合点):
     * <pre>
     *   Object[] args = { box(p0), ... };
     *   Object r = GroovierHooks.enter(key, thiz|null, args);
     *   if (r != null) return unbox(r);            // override:脚本覆盖返回值
     *   // Lcont(F_FULL 帧):
     *   result = this|static.groovier$orig$&lt;name&gt;(p0, ...);
     *   GroovierHooks.exit(key, thiz|null, args, box(result));  // on 伪事件(仅原方法实际执行时)
     *   return result;
     * </pre>
     */
    private void wrapMethod(ClassNode node, MethodNode mn, String key, String methodName) {
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        Type ret = Type.getReturnType(mn.desc);
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        // 目标类是接口(static/default 方法钉)时,原方法调用须走 InterfaceMethodref:
        // ASM 按 isInterface 选常量池 tag(10/11),itf=false 首次执行抛 IncompatibleClassChangeError
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;

        // 局部变量槽位:receiver(非 static)+ 参数 + args[] + r + result
        int[] argSlots = new int[argTypes.length];
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            argSlots[i] = slot;
            slot += argTypes[i].getSize();
        }
        int arrSlot = slot;
        int rSlot = arrSlot + 1;
        int resultSlot = rSlot + 1;
        int maxLocals = resultSlot + ret.getSize();
        // 最大栈:按 desc 精确计算各分支峰值(long/double 占 2 槽,JVMS):
        //   装箱参数 arr+idx+arg 峰值 4;enter 调用 key+thiz+args = 3;
        //   调原方法 Σ参数槽 + receiver(非 static):非静态 (IIII)V=5、静态 (JJJ)V=6;
        //   exit 调用 key+thiz+args+result,返回 long/double 时 = 5
        int argSlotSum = 0;
        for (Type t : argTypes) {
            argSlotSum += t.getSize();
        }
        int maxStack = Math.max(argSlotSum + (isStatic ? 0 : 1), ret.getSize() == 2 ? 5 : 4);

        // 1. 原方法改名(同名冲突则补 $ 直至可用)
        String origName = ORIG_PREFIX + methodName;
        while (methodNameExists(node, origName, mn.desc)) {
            origName = origName + "$";
        }
        mn.name = origName;

        // 2. 同名包装方法(保留原修饰符:同步语义重入保持,VarArgs 调用约定一致),
        // 加标 ACC_SYNTHETIC:isExcluded 据此识别包装体,防止被后续 plan 重复包装
        // 注:不复制 throws 清单(verifier 不强制;反射 getExceptionTypes 有差异,已知取舍)
        MethodNode wrapper = new MethodNode(mn.access | Opcodes.ACC_SYNTHETIC, methodName, mn.desc, mn.signature, null);
        node.methods.add(node.methods.indexOf(mn) + 1, wrapper);

        // 2a. Object[] args = { box(p0), ... }
        // ANEWARRAY 是类型指令(操作数 = 组件类型符号,visitTypeInsn),且必须先压入数组长度 int
        pushInt(wrapper, argTypes.length);
        wrapper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, arrSlot));
        for (int i = 0; i < argTypes.length; i++) {
            Type t = argTypes[i];
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, arrSlot));
            pushInt(wrapper, i);
            wrapper.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), argSlots[i]));
            if (t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY) {
                String[] box = boxing(t);
                wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box[0], "valueOf", box[1], false));
            }
            wrapper.instructions.add(new InsnNode(Opcodes.AASTORE));
        }

        // 2b. Object r = enter(key, thiz|null, args); if (r != null) return unbox(r);
        wrapper.instructions.add(new LdcInsnNode(key));
        if (isStatic) {
            wrapper.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, arrSlot));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "enter", ENTER_DESC, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, rSlot));
        LabelNode cont = new LabelNode(new Label());
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, rSlot));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, cont));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, rSlot));
        emitReturnValue(wrapper, ret);

        // 2c. Lcont:汇合点帧(唯一手写帧):locals = receiver + 参数 + args[] + r(Object),栈空
        List<Object> locals = new ArrayList<>();
        if (!isStatic) {
            locals.add(node.name);
        }
        for (Type t : argTypes) {
            locals.add(frameLocal(t));
        }
        locals.add("[Ljava/lang/Object;");
        locals.add("java/lang/Object;");
        wrapper.instructions.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 0, new Object[0]));
        wrapper.instructions.add(cont);

        // 2d. 调原方法(itf 按目标类是否接口生成,见上方 isInterface 说明)
        for (int i = 0; i < argTypes.length; i++) {
            Type t = argTypes[i];
            wrapper.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), argSlots[i]));
        }
        if (isStatic) {
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, origName, mn.desc, isInterface));
        } else {
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, origName, mn.desc, isInterface));
        }

        // 2e. exit 伪事件 + 返回(先落栈 result,再压钩子参数,栈序不交叉)
        if (ret.getSort() == Type.VOID) {
            pushHookArgs(wrapper, key, isStatic, arrSlot);
            wrapper.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // result = null
            emitExit(wrapper);
            wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            wrapper.instructions.add(new VarInsnNode(ret.getOpcode(Opcodes.ISTORE), resultSlot));
            pushHookArgs(wrapper, key, isStatic, arrSlot);
            wrapper.instructions.add(new VarInsnNode(ret.getOpcode(Opcodes.ILOAD), resultSlot));
            if (ret.getSort() != Type.OBJECT && ret.getSort() != Type.ARRAY) {
                String[] box = boxing(ret);
                wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box[0], "valueOf", box[1], false));
            }
            emitExit(wrapper);
            wrapper.instructions.add(new VarInsnNode(ret.getOpcode(Opcodes.ILOAD), resultSlot));
            wrapper.instructions.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        }
        wrapper.maxStack = maxStack;
        wrapper.maxLocals = maxLocals;
        wrapper.visitEnd();

        // 数据流自检:栈下溢/类型错在此抛 AnalyzerException → transform 顶层 fail-safe 返回原字节,
        // 字节码生成缺陷不再以运行时崩溃的形式暴露(report 标 error)。BasicVerifier 对不可解析的
        // mod/MC 类型按引用值宽松处理,只严格校验栈纪律,无需加载目标类。
        try {
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier())
                    .analyze(node.name, wrapper);
        } catch (org.objectweb.asm.tree.analysis.AnalyzerException e) {
            throw new IllegalStateException("pin wrapper failed dataflow self-check on " + key, e);
        }
    }

    /** 压入 exit 调用的前三参:key, thiz|null, args[]。 */
    private void pushHookArgs(MethodNode wrapper, String key, boolean isStatic, int arrSlot) {
        wrapper.instructions.add(new LdcInsnNode(key));
        if (isStatic) {
            wrapper.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, arrSlot));
    }

    private void emitExit(MethodNode wrapper) {
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "exit", EXIT_DESC, false));
    }

    /** override 返回:引用类型 checkcast 后返回;void 丢弃覆盖值;基本类型拆箱。 */
    private void emitReturnValue(MethodNode wrapper, Type ret) {
        switch (ret.getSort()) {
            case Type.VOID -> {
                // 丢弃覆盖值后必须 RETURN:若只 POP 不返回,会掉进 cont 汇合点继续执行原方法
                // (栈纪律合法、Analyzer/JVM 验证均通过,纯语义缺陷——override 永不生效)
                wrapper.instructions.add(new InsnNode(Opcodes.POP));
                wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN -> castAndUnbox(wrapper, "java/lang/Boolean", "booleanValue", "()Z", Opcodes.IRETURN);
            case Type.BYTE -> castAndUnbox(wrapper, "java/lang/Byte", "byteValue", "()B", Opcodes.IRETURN);
            case Type.SHORT -> castAndUnbox(wrapper, "java/lang/Short", "shortValue", "()S", Opcodes.IRETURN);
            case Type.INT -> castAndUnbox(wrapper, "java/lang/Integer", "intValue", "()I", Opcodes.IRETURN);
            case Type.CHAR -> castAndUnbox(wrapper, "java/lang/Character", "charValue", "()C", Opcodes.IRETURN);
            case Type.LONG -> castAndUnbox(wrapper, "java/lang/Long", "longValue", "()J", Opcodes.LRETURN);
            case Type.FLOAT -> castAndUnbox(wrapper, "java/lang/Float", "floatValue", "()F", Opcodes.FRETURN);
            case Type.DOUBLE -> castAndUnbox(wrapper, "java/lang/Double", "doubleValue", "()D", Opcodes.DRETURN);
            default -> {
                wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getInternalName()));
                wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    private void castAndUnbox(MethodNode wrapper, String boxType, String name, String desc, int returnOpcode) {
        wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, boxType));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxType, name, desc, false));
        wrapper.instructions.add(new InsnNode(returnOpcode));
    }

    private static String[] boxing(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> new String[] {"java/lang/Boolean", "(Z)Ljava/lang/Boolean;"};
            case Type.BYTE -> new String[] {"java/lang/Byte", "(B)Ljava/lang/Byte;"};
            case Type.SHORT -> new String[] {"java/lang/Short", "(S)Ljava/lang/Short;"};
            case Type.CHAR -> new String[] {"java/lang/Character", "(C)Ljava/lang/Character;"};
            case Type.INT -> new String[] {"java/lang/Integer", "(I)Ljava/lang/Integer;"};
            case Type.LONG -> new String[] {"java/lang/Long", "(J)Ljava/lang/Long;"};
            case Type.FLOAT -> new String[] {"java/lang/Float", "(F)Ljava/lang/Float;"};
            case Type.DOUBLE -> new String[] {"java/lang/Double", "(D)Ljava/lang/Double;"};
            default -> throw new IllegalArgumentException("not a primitive: " + t);
        };
    }

    private static Object frameLocal(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> Opcodes.INTEGER;
            case Type.LONG -> Opcodes.LONG;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            default -> t.getInternalName();
        };
    }

    private static void pushInt(MethodNode wrapper, int v) {
        if (v >= 0 && v <= 5) {
            wrapper.instructions.add(new InsnNode(Opcodes.ICONST_0 + v));
        } else if (v >= -128 && v <= 127) {
            wrapper.instructions.add(new IntInsnNode(Opcodes.BIPUSH, v));
        } else if (v >= -32768 && v <= 32767) {
            wrapper.instructions.add(new IntInsnNode(Opcodes.SIPUSH, v));
        } else {
            wrapper.instructions.add(new LdcInsnNode(v));
        }
    }

    private static boolean methodNameExists(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
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
