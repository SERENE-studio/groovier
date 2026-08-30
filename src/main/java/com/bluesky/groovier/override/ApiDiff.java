package com.bluesky.groovier.override;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 6.4.2 API 比对工具(签名契约防线 3,SPEC §6.4.2):比对基准类字节与覆盖类字节的
 * API 面,报告覆盖类缺失的对外成员(破坏性变更 → 阻止应用,防调用方 NoSuchMethodError)。
 *
 * <p>契约成员 = 非 synthetic/bridge 的方法(含构造器)与字段;{@code groovier$} 前缀
 * (钉子包装产物)与 {@code <clinit>} 不属于类契约。public/protected/package-private
 * 缺失 = 破坏性(package-private 是同包跨类真实链接面);private 缺失 = 告警级
 * (同 nest 的内部类经 nestmate 访问私有成员,缺失会 IllegalAccessError,但不影响
 * 外部调用方);新增 = 信息级(Groovy 产物会附加 GroovyObject 桥接等辅助成员)。
 * 覆盖类经编译器再生的桥方法(泛型协变覆盖)按擦除签名并入命中集合,不作为独立契约项;
 * superName 变更(除自引用改写)与 static↔instance 互换同为破坏性。
 */
public final class ApiDiff {

    /** missing = 破坏性(阻止应用);missingPrivate = 告警;added/notes = 信息。 */
    public record Result(List<String> missing, List<String> missingPrivate, List<String> added, List<String> notes) {
        public boolean breaking() {
            return !missing.isEmpty();
        }
    }

    private ApiDiff() {}

    public static Result diff(byte[] baseline, byte[] override) {
        ClassNode base = read(baseline);
        ClassNode over = read(override);
        Set<String> baseExternal = members(base, true);
        Set<String> basePrivate = members(base, false);
        Set<String> overExternal = members(over, true);
        Set<String> overPrivate = members(over, false);
        Set<String> overBridge = bridgeSignatures(over);

        List<String> missing = new ArrayList<>();
        for (String m : baseExternal) {
            if (!overExternal.contains(m) && !overBridge.contains(m)) {
                missing.add(m);
            }
        }
        List<String> missingPrivate = new ArrayList<>();
        for (String m : basePrivate) {
            if (!overPrivate.contains(m) && !overExternal.contains(m) && !overBridge.contains(m)) {
                missingPrivate.add(m);
            }
        }
        List<String> added = new ArrayList<>();
        for (String m : overExternal) {
            if (!baseExternal.contains(m)) {
                added.add(m);
            }
        }

        List<String> notes = new ArrayList<>();
        // 覆盖类 extends 目标自身(refer 模板复刻写法)时,替换侧会改指原父类,视为一致;
        // 其余 superName 变更(如覆盖类漏写 extends 落到 Object)阻断:继承解析的调用与
        // instanceof/checkcast 全部失效
        String overSuper = over.superName.equals(over.name) ? base.superName : over.superName;
        if (!overSuper.equals(base.superName)) {
            missing.add("superName: " + base.superName + " -> " + overSuper);
        }
        for (String i : base.interfaces) {
            if (!over.interfaces.contains(i)) {
                missing.add("interface " + i);
            }
        }
        for (String i : over.interfaces) {
            if (!base.interfaces.contains(i)) {
                added.add("interface " + i);
            }
        }
        return new Result(missing, missingPrivate, added, notes);
    }

    /** API 面成员键;external=true 取 public/protected/package-private(同包跨类链接面),否则取 private。 */
    private static Set<String> members(ClassNode node, boolean external) {
        Set<String> out = new LinkedHashSet<>();
        for (MethodNode mn : node.methods) {
            if (mn.name.equals("<clinit>")
                    || mn.name.startsWith("groovier$")
                    || (mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                continue;
            }
            if (((mn.access & Opcodes.ACC_PRIVATE) != 0) == external) {
                continue;
            }
            out.add(methodKey(mn));
        }
        for (FieldNode fn : node.fields) {
            if (fn.name.startsWith("groovier$") || (fn.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            if (((fn.access & Opcodes.ACC_PRIVATE) != 0) == external) {
                continue;
            }
            out.add(((fn.access & Opcodes.ACC_STATIC) != 0 ? "static field " : "field ")
                    + fn.name + " " + fn.desc);
        }
        return out;
    }

    /** 成员键含 static 位:static↔instance 互换视为不同成员,防 ICCE/NSME。 */
    private static String methodKey(MethodNode mn) {
        String base = mn.name.equals("<init>") ? "ctor " + mn.desc : "method " + mn.name + mn.desc;
        return (mn.access & Opcodes.ACC_STATIC) != 0 ? "static " + base : base;
    }

    /** 覆盖类编译器再生桥(泛型协变覆盖)的擦除签名:仅参与契约命中,不进 added/独立契约。 */
    private static Set<String> bridgeSignatures(ClassNode node) {
        Set<String> out = new LinkedHashSet<>();
        for (MethodNode mn : node.methods) {
            if ((mn.access & Opcodes.ACC_BRIDGE) != 0) {
                out.add(methodKey(mn));
            }
        }
        return out;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }
}
