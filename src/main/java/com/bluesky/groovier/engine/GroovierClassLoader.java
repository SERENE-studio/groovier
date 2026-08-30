package com.bluesky.groovier.engine;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

import java.util.function.Consumer;

/**
 * 脚本类加载器。
 * 父加载器 = groovier 自身的 mod 类加载器:NeoForge 中所有 mod 类平级互见、游戏类在父链中,
 * 故脚本类可访问游戏类与任意 mod 类(与 1.12.2 GroovyScript 挂 Launch.classLoader 同构)。
 * 关闭自动重编译;热重载时整体丢弃并新建,避免脚本类泄漏。
 * 层1增量缓存:重写 createCollector 返回捕获型 ClassCollector(parseClass 时会将其设为
 * classgenCallback),把每次编译产出的 (类名 -> 字节码) 经可注入回调收集,供 ScriptManager
 * 跨 reload 复用字节码(脚本文件未变更时直接 defineClass,跳过 Groovy 编译)。
 */
public class GroovierClassLoader extends GroovyClassLoader {

    // 编译产出类字节码的捕获回调(M6:ThreadLocal 线程隔离,并发编译会话互不污染;transient:随 loader 生命周期)
    private final transient ThreadLocal<Consumer<CompiledClass>> compiledClassConsumer = new ThreadLocal<>();

    public GroovierClassLoader(ClassLoader parent, CompilerConfiguration config) {
        super(parent, config, false);
        setShouldRecompile(false);
    }

    /** 注入编译产出回调(每次编译会话前设置,编译结束后清空为 null;仅对当前编译线程生效)。 */
    public void setCompiledClassConsumer(Consumer<CompiledClass> consumer) {
        this.compiledClassConsumer.set(consumer);
    }

    @Override
    protected ClassCollector createCollector(CompilationUnit unit, SourceUnit su) {
        return new CapturingClassCollector(new InnerLoader(this), unit, su);
    }

    /** 捕获型收集器:先按父类逻辑正常定义类,再把 (实际类名, 字节码) 交给回调。 */
    private class CapturingClassCollector extends ClassCollector {

        CapturingClassCollector(InnerLoader innerLoader, CompilationUnit unit, SourceUnit su) {
            super(innerLoader, unit, su);
        }

        @Override
        protected Class<?> createClass(byte[] code, ClassNode classNode) {
            Class<?> clazz = super.createClass(code, classNode);
            Consumer<CompiledClass> consumer = compiledClassConsumer.get();
            if (consumer != null) {
                consumer.accept(new CompiledClass(clazz.getName(), code));
            }
            return clazz;
        }
    }

    /** 编译产出的单个类:实际类名 + 字节码(供字节码缓存按类名逐个 defineClass 复用)。 */
    public record CompiledClass(String className, byte[] code) {}
}
