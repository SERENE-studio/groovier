package com.bluesky.groovier.engine;

import com.bluesky.groovier.api.GroovyLog;
import com.bluesky.groovier.event.GroovierEventManager;
import com.bluesky.groovier.sandbox.ScriptManager;
import groovy.lang.Binding;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 脚本引擎:编译并执行单个脚本文件。
 * 脚本文件(继承 Script)→ 创建脚本实例注入绑定执行;纯类文件 → 调用 $getLookup 触发类初始化。
 * 执行期间将脚本相对路径设为事件管理器上下文,使注册的监听器归属该脚本(便于禁用时注销)。
 * 层1/层2:编译与执行拆分为独立钩子,供增量重载(prepare 阶段经 compileSource 编译,
 * apply 阶段执行)与同步单脚本执行(runFile)共用。
 */
public class ScriptEngine {

    private final GroovierClassLoader classLoader;
    private final Binding binding;

    public ScriptEngine(GroovierClassLoader classLoader, Binding binding) {
        this.classLoader = classLoader;
        this.binding = binding;
    }

    /** 编译脚本文件,返回主脚本类。不执行、不登记(runFile 同步单脚本路径)。 */
    public Class<?> compileFile(File file, String relPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            // 以相对路径编码的唯一类名作为源文件名,避免子目录同名脚本生成同类导致缓存覆盖
            return classLoader.parseClass(reader, uniqueClassName(relPath));
        }
    }

    /** 编译脚本源文本,返回主脚本类(L13:loadOrCompile 传入与哈希同源的一次性读出内容,不重复读文件)。 */
    public Class<?> compileSource(String source, String relPath) {
        return classLoader.parseClass(source, uniqueClassName(relPath));
    }

    /** 执行已编译脚本:登记类名映射 → 创建脚本实例注入绑定执行(纯类调用 $getLookup 触发静态初始化)。 */
    public void executeScript(Class<?> clazz, String relPath) throws Exception {
        GroovierEventManager.INSTANCE.setCurrentScript(relPath);
        try {
            // 登记类名 -> 相对路径,供事件回调内注册监听器时反查归属脚本
            ScriptManager.registerScriptClass(clazz.getName(), relPath);
            if (Script.class.isAssignableFrom(clazz)) {
                Script script = InvokerHelper.createScript(clazz, binding);
                script.run();
            } else {
                // Groovy 生成的普通类:调用 $getLookup 触发静态初始化
                Method lookup = clazz.getMethod("$getLookup");
                lookup.invoke(null);
            }
        } finally {
            GroovierEventManager.INSTANCE.setCurrentScript(null);
        }
    }

    /** 编译并执行脚本(同步单脚本路径)。返回是否成功:编译或执行抛异常时回滚已注册监听器并返回 false,Error 放行。 */
    public boolean runFile(File file, String relPath) {
        GroovierEventManager.INSTANCE.setCurrentScript(relPath);
        GroovyLog.INSTANCE.info("Running script {}", file);
        try {
            Class<?> clazz = compileFile(file, relPath);
            executeScript(clazz, relPath);
            return true;
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            GroovyLog.INSTANCE.error("Failed to run script {}: {}", relPath, cause);
            // 失败回滚:脚本初始化中途抛异常时,已注册的监听器与钩子一并注销,防止半初始化状态残留
            GroovierEventManager.INSTANCE.unregisterScript(relPath);
            com.bluesky.groovier.hooks.GroovierHooks.unregisterScript(relPath);
            return false;
        } finally {
            GroovierEventManager.INSTANCE.setCurrentScript(null);
        }
    }

    /** 解包反射调用异常:InvocationTargetException 的真实原因在其 cause 中,直接记录 cause 便于定位问题。 */
    public static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return t;
    }

    /**
     * 将脚本相对路径编码为唯一类名(L7):字母数字原样保留,其余字符(含 -/./中文/_)一律转义为
     * "_" + 4 位十六进制,编码可逆且无碰撞——修复 a-b.groovy 与 a_b.groovy 折叠为同名类导致的
     * 缓存/defineClass 冲突。前缀 "Groovier_" 保证合法 Java 标识符且不与脚本显式声明类冲突。
     */
    public static String uniqueClassName(String relPath) {
        String base = relPath.substring(0, relPath.length() - ".groovy".length());
        StringBuilder sb = new StringBuilder("Groovier_");
        for (char c : base.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_').append(String.format("%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
