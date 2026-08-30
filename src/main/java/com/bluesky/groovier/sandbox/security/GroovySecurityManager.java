package com.bluesky.groovier.sandbox.security;

import com.bluesky.groovier.api.GlobalManager;
import com.bluesky.groovier.api.GroovyBlacklist;
import com.bluesky.groovier.engine.GroovierClassLoader;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.MetaClassRegistry;
import groovy.ui.GroovyMain;
import groovy.util.Eval;
import groovy.util.GroovyScriptEngine;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.runtime.ProcessGroovyMethods;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * 沙箱黑名单(宽松默认 + 高危拦截):
 * - 包黑名单:java.net(网络)、java.nio.channels、java.security、java.rmi、groovy.grape、sun.、com.sun. 等
 * - 类黑名单:Runtime/ProcessBuilder/ClassLoader/Scanner 等直接逃逸工具 + Groovier 自身组件自保(G3)
 * - 类层级判定:沿 superclass 与接口链上溯匹配(G1,防子类/实现类绕过)
 * - 方法黑名单:System.exit/gc/setSecurityManager/load/loadLibrary、Class.getResource(AsStream)、String.execute 等
 * - 白名单:豁免类(内部必要类)
 * - @GroovyBlacklist:groovier 内部敏感 API 标记
 * 允许:反射(java.lang.reflect)、文件 IO(java.io)等,贴合"直改运行时"定位。
 * 拦截机制:类/方法黑名单在编译期(AST,GroovierTransformer)判定;运行时仅类级拦截(MetaClass 黑名单,GrSMetaClassCreationHandle)。
 * 方法/字段级拦截只在编译期生效,反射可绕过(与 SPEC 一致的已知限制)。
 */
public class GroovySecurityManager {

    public static final GroovySecurityManager INSTANCE = new GroovySecurityManager();

    private final List<String> bannedPackages = new ArrayList<>();
    private final Set<String> bannedClasses = new HashSet<>();
    private final Map<String, Set<String>> bannedMethods = new HashMap<>();
    private final Set<String> whiteListedClasses = new HashSet<>();

    private GroovySecurityManager() {
        initDefaults();
    }

    private void initDefaults() {
        // 网络 / 系统逃逸 / 外部依赖拉取
        banPackage("java.net");
        banPackage("javax.net");
        banPackage("java.rmi");
        banPackage("java.security");
        banPackage("groovy.grape");
        banPackage("sun.");
        // G5:NIO 通道(文件句柄/网络套接字低层)与 com.sun 内部 API
        banPackage("java.nio.channels");
        banPackage("com.sun.");
        // G3:Groovier 自身组件自保——防脚本调用 unBanClass/banClass/banPackage/banMethods、
        // new ScriptManager()(构造器会劫持 GlobalManager.setOnChange)破坏运行时
        banPackage("com.bluesky.groovier.sandbox");
        banPackage("com.bluesky.groovier.engine");
        // G3:api 包单类自保(globals 读写仍走绑定对象不受影响;拦显式引用防 setOnChange 劫持回调)
        banClass(GlobalManager.class);
        // 直接逃逸工具类
        banClasses(Runtime.class, ProcessBuilder.class, ClassLoader.class, Scanner.class);
        banClasses(GroovyShell.class, GroovyClassLoader.class, GroovyScriptEngine.class, Eval.class, GroovyMain.class);
        // G1:显式 ban——脚本用它 + 无沙箱 CompilerConfiguration 二次编译即可绕过沙箱
        banClass(GroovierClassLoader.class);
        // 高危方法
        banMethods(System.class, "exit", "gc", "setSecurityManager");
        // M9:本地库加载逃逸面
        banMethods(System.class, "load", "loadLibrary");
        banMethods(Class.class, "getResource", "getResourceAsStream");
        banMethods(String.class, "execute");
        banMethods(ProcessGroovyMethods.class, "execute");
        banMethods(Thread.class, "stop", "suspend", "resume");
        // G2a:封堵经公开 MetaClassRegistry API 换 handle/MetaClass 重建正常 MetaClass 绕过运行时拦截
        // (不整类 ban MetaClassRegistry:脚本正常的 EMC 用法 Foo.metaClass.bar = {} 需保留)
        banMethods(MetaClassRegistry.class, "setMetaClassCreationHandle", "setMetaClass", "removeMetaClass");
    }

    public void unBanClass(Class<?> clazz) {
        whiteListedClasses.add(clazz.getName());
    }

    public void banPackage(String packageName) {
        bannedPackages.add(packageName);
    }

    public void banClass(Class<?> clazz) {
        bannedClasses.add(clazz.getName());
    }

    public void banClasses(Class<?>... classes) {
        for (Class<?> clazz : classes) banClass(clazz);
    }

    public void banMethods(Class<?> clazz, String... methods) {
        bannedMethods.computeIfAbsent(clazz.getName(), k -> new HashSet<>()).addAll(List.of(methods));
    }

    // ---------- 编译期判定(ClassNode) ----------

    public boolean isValid(ClassNode classNode) {
        return whiteListedClasses.contains(classNode.getName())
                || (!isBannedClass(classNode.getName())
                && !hasBlacklistAnnotation(classNode)
                && isValidPackage(classNode.getName()));
    }

    public boolean isBannedPackage(String className) {
        for (String pkg : bannedPackages) {
            if (className.startsWith(pkg)) return true;
        }
        return false;
    }

    public boolean isValidPackage(String className) {
        return !isBannedPackage(className);
    }

    public boolean isBannedClass(String className) {
        if (bannedClasses.contains(className)) return true;
        // G1:类层级判定——可加载的类沿 superclass 与接口链上溯匹配黑名单(防子类/实现类绕过)
        try {
            return isBannedClass(Class.forName(className, false, GroovySecurityManager.class.getClassLoader()));
        } catch (Throwable t) {
            // 不可加载(脚本自身尚未编译的类等)退回 FQN 精确匹配
            return false;
        }
    }

    /** G1:沿 superclass 与接口闭包上溯匹配类黑名单。 */
    public boolean isBannedClass(Class<?> clazz) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            if (bannedClasses.contains(c.getName())) return true;
            pending.push(c);
        }
        while (!pending.isEmpty()) {
            for (Class<?> iface : pending.pop().getInterfaces()) {
                if (visited.add(iface)) {
                    if (bannedClasses.contains(iface.getName())) return true;
                    pending.push(iface);
                }
            }
        }
        return false;
    }

    public boolean isBannedMethod(String receiverClass, String method) {
        Set<String> methods = bannedMethods.get(receiverClass);
        return methods != null && methods.contains(method);
    }

    public static boolean hasBlacklistAnnotation(ClassNode classNode) {
        for (AnnotationNode ann : classNode.getAnnotations()) {
            if (ann.getClassNode() != null && ann.getClassNode().getName().equals(GroovyBlacklist.class.getName())) {
                return true;
            }
        }
        return false;
    }

    // ---------- 运行时判定(Class) ----------

    public boolean isValid(Class<?> clazz) {
        return whiteListedClasses.contains(clazz.getName())
                || (!isBannedClass(clazz)
                && !clazz.isAnnotationPresent(GroovyBlacklist.class)
                && isValidPackage(clazz.getName()));
    }
}
