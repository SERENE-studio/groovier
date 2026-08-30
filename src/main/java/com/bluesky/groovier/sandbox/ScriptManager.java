package com.bluesky.groovier.sandbox;

import com.bluesky.groovier.api.GlobalManager;
import com.bluesky.groovier.api.GroovyLog;
import com.bluesky.groovier.engine.GroovierClassLoader;
import com.bluesky.groovier.engine.ScriptEngine;
import com.bluesky.groovier.event.GroovierEventManager;
import com.bluesky.groovier.sandbox.security.GrSMetaClassCreationHandle;
import groovy.lang.Binding;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClassRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 脚本管理器:脚本注册表 + 动态启用/禁用。
 * - 启用状态约定:globals 布尔键 groovier.enabled.<相对路径>(缺省=启用)
 * - GlobalManager 变更时检测状态翻转 → 启用:执行脚本;禁用:注销其监听器
 * 层1增量缓存:compiledCache 按相对路径缓存"文件内容哈希 + 全部类字节码",
 * reload 时未变更脚本跳过 Groovy 编译,直接在新类加载器上 defineClass 复用字节码;
 * 层2后台重载:prepare(任意线程,扫描+增量编译,不碰事件总线) + apply(必须 server thread,
 * 注销旧监听+重建注册表+执行脚本),apply 不做任何编译,耗时极短不阻塞 tick。
 */
public class ScriptManager {

    public static final String ENABLED_PREFIX = "groovier.enabled.";

    // M8(b):ConcurrentHashMap——onGlobalChanged 迭代与 apply 重建并发安全
    private final Map<String, ScriptState> scripts = new ConcurrentHashMap<>();

    // 脚本类名 -> 脚本相对路径(编译期登记,供事件监听器归属反查)
    private static final Map<String, String> scriptClasses = new ConcurrentHashMap<>();

    // 层1增量缓存:相对路径 -> 编译产物(文件哈希 + 类名->字节码)。reload 必须保留才能增量,
    // 仅当脚本不再存在于本次扫描时才清理对应条目。
    private final Map<String, CachedScript> compiledCache = new HashMap<>();

    // 层2并发保护:后台重载进行中标志(防玩家在编译期间重复触发 reload)
    private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);

    // globals 变更触发的状态检测,脚本执行中 set globals 会嵌套触发,防重入(M8(a):跨线程可见)
    private volatile boolean inChange = false;

    private GroovierClassLoader classLoader;
    private Binding binding;
    private File root;

    public ScriptManager() {
        GlobalManager.INSTANCE.setOnChange(this::onGlobalChanged);
    }

    /** 全量执行(初始化/reload 的同步路径):prepare + apply 同线程完成,同样受益于层1增量缓存。 */
    public void runAll(GroovierClassLoader loader, Binding binding, File root) {
        apply(prepare(loader, binding, root));
    }

    /** 准备阶段(任意线程):扫描脚本 + 增量编译/加载,产出成功/失败/禁用列表。不执行脚本、不碰事件总线。 */
    public ReloadResult prepare(GroovierClassLoader loader, Binding template, File root) {
        long start = System.currentTimeMillis();
        ReloadResult result = new ReloadResult(loader, template, root);
        List<String> paths = new ArrayList<>();
        if (root != null && root.isDirectory()) {
            collectScripts(root, "", paths);
            paths.sort(Comparator.naturalOrder());
        } else {
            GroovyLog.INSTANCE.info("Groovier scripts directory {} does not exist, skipping.", root);
        }
        // 清理缓存:本次扫描中已不存在的脚本条目移除(仅清"脚本已删除",未变更脚本的缓存保留)
        Set<String> scanned = new HashSet<>(paths);
        compiledCache.keySet().removeIf(p -> !scanned.contains(p));
        for (String path : paths) {
            boolean enabled = isEnabled(path);
            result.states.put(path, new ScriptState(path, enabled));
            if (!enabled) {
                GroovyLog.INSTANCE.info("Script {} is disabled, skipping.", path);
                result.disabledCount++;
                continue;
            }
            try {
                result.successful.put(path, loadOrCompile(loader, new File(root, path), path, template));
            } catch (Throwable t) {
                GroovyLog.INSTANCE.error("Failed to prepare script {}: {}", path, t);
                result.failed.add(path);
                compiledCache.remove(path); // 编译失败不保留缓存条目
            }
        }
        result.prepareMillis = System.currentTimeMillis() - start;
        return result;
    }

    /** 应用阶段(必须 server thread):注销旧监听 → 重建注册表 → 执行全部成功脚本。不做任何编译。 */
    public void apply(ReloadResult result) {
        long start = System.currentTimeMillis();
        this.classLoader = result.loader;
        this.binding = result.template;
        this.root = result.root;
        GroovierEventManager.INSTANCE.reset();
        com.bluesky.groovier.hooks.GroovierHooks.reset();
        scripts.clear();
        scriptClasses.clear();
        scripts.putAll(result.states);
        for (Map.Entry<String, Class<?>> entry : result.successful.entrySet()) {
            String relPath = entry.getKey();
            // M8(c):prepare 快照后复核 enable 状态——快照期间脚本可能已被 /groovier disable
            if (!isEnabled(relPath)) {
                ScriptState state = scripts.get(relPath);
                if (state != null) state.enabled = false;
                GroovyLog.INSTANCE.info("Script {} disabled since prepare, skipping.", relPath);
                continue;
            }
            try {
                // M8(d):执行脚本期间置 inChange——脚本内 set globals 不应嵌套触发重入检测
                inChange = true;
                try {
                    ensureSandboxHandle();
                    GroovyLog.INSTANCE.info("Running script {}", relPath);
                    // 每个脚本独立 Binding(顶层变量隔离):浅拷贝模板变量,跨脚本共享仍走 globals
                    new ScriptEngine(classLoader, new Binding(new HashMap<>(binding.getVariables()))).executeScript(entry.getValue(), relPath);
                } finally {
                    inChange = false;
                }
            } catch (Exception e) {
                Throwable cause = ScriptEngine.unwrap(e);
                GroovyLog.INSTANCE.error("Failed to run script {}: {}", relPath, cause);
                // 失败回滚:脚本初始化中途抛异常时,已注册的监听器一并注销,防止半初始化状态残留
                GroovierEventManager.INSTANCE.unregisterScript(relPath);
                com.bluesky.groovier.hooks.GroovierHooks.unregisterScript(relPath);
                result.failed.add(relPath);
                result.states.get(relPath).failed = true; // 执行失败标记第三态,列表输出显示 [FAILED]
            }
        }
        result.applyMillis = System.currentTimeMillis() - start;
        GroovyLog.INSTANCE.info("Groovier reload finished: prepare {} ms, apply {} ms, {} listeners active, {} scripts failed.",
                result.prepareMillis, result.applyMillis, GroovierEventManager.INSTANCE.listenerCount(), result.failed.size());
        for (String path : result.failed) {
            GroovyLog.INSTANCE.error("Failed script: {}", path);
        }
    }

    /**
     * 增量判定与加载:
     * - 文件哈希与缓存一致 → 直接用缓存字节码在新 loader 上逐个 defineClass(不触发静态初始化,线程安全);
     * - 变更/新增 → parseClass 编译,并借 ClassCollector 捕获字节码更新缓存。
     */
    private Class<?> loadOrCompile(GroovierClassLoader loader, File file, String relPath, Binding template) throws Exception {
        String mainClassName = ScriptEngine.uniqueClassName(relPath);
        // L13:一次读出全部字节,哈希与编译使用同一份内容,避免"哈希新/字节码旧"错误配对持久化
        byte[] content = Files.readAllBytes(file.toPath());
        String hash = hashBytes(content);
        CachedScript cached = compiledCache.get(relPath);
        if (cached != null && cached.hash.equals(hash) && cached.classes.containsKey(mainClassName)) {
            // 缓存命中:在新 loader 上定义全部类(defineClass 不做链接/静态初始化,可安全在后台线程完成)
            Class<?> mainClass = null;
            for (Map.Entry<String, byte[]> entry : cached.classes.entrySet()) {
                Class<?> defined = loader.defineClass(entry.getKey(), entry.getValue());
                if (entry.getKey().equals(mainClassName)) mainClass = defined;
            }
            GroovyLog.INSTANCE.info("Script {} unchanged, reused {} cached class(es).", relPath, cached.classes.size());
            return mainClass;
        }
        // 变更/新增:编译,并捕获全部产出类的字节码更新缓存
        List<GroovierClassLoader.CompiledClass> captured = new ArrayList<>();
        loader.setCompiledClassConsumer(captured::add);
        try {
            Class<?> clazz = new ScriptEngine(loader, template).compileSource(new String(content, StandardCharsets.UTF_8), relPath);
            Map<String, byte[]> classes = new HashMap<>();
            for (GroovierClassLoader.CompiledClass cc : captured) {
                classes.put(cc.className(), cc.code());
            }
            compiledCache.put(relPath, new CachedScript(hash, classes));
            return clazz;
        } finally {
            loader.setCompiledClassConsumer(null);
        }
    }

    /** 计算内容 SHA-256(脚本文件小,读全文算哈希比 mtime+length 更稳)。 */
    private String hashBytes(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private void collectScripts(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                // 工具产物目录不作为脚本执行(refer = 6.5 反编译模板;override = 6.4 整类覆盖源,
                // 由 OverrideManager 影子编译,执行会误触发目标类结构)
                if (prefix.isEmpty() && ("refer".equals(child.getName()) || "override".equals(child.getName()))) {
                    continue;
                }
                collectScripts(child, prefix + child.getName() + "/", out);
            } else if (child.getName().endsWith(".groovy")) {
                out.add(prefix + child.getName());
            }
        }
    }

    /** 编译并执行单个脚本(启用时调用,同步路径:globals 动态启用)。 */
    public void runScript(String relPath) {
        // M8(c):重载进行中或尚未初始化时拒绝动态启用,避免在陈旧快照/未就绪环境上执行
        if (reloadInProgress.get() || classLoader == null || root == null) {
            GroovyLog.INSTANCE.warn("Script {} enable request ignored (reload in progress or not initialized).", relPath);
            return;
        }
        ensureSandboxHandle();
        ScriptState state = scripts.get(relPath);
        try {
            boolean ok = new ScriptEngine(classLoader, new Binding(new HashMap<>(binding.getVariables())))
                    .runFile(new File(root, relPath), relPath);
            // 备注②:动态启用成功后清除 failed 标志;失败(runFile 内已回滚监听)则标记
            if (state != null) state.failed = !ok;
        } catch (Throwable t) {
            // M8(e):异常不打断触发方,回滚已注册监听并标记失败
            GroovyLog.INSTANCE.error("Failed to run script {}: {}", relPath, t);
            GroovierEventManager.INSTANCE.unregisterScript(relPath);
            com.bluesky.groovier.hooks.GroovierHooks.unregisterScript(relPath);
            if (state != null) state.failed = true;
        }
    }

    /** 登记脚本类名与相对路径的映射(脚本编译后由 ScriptEngine 调用)。 */
    public static void registerScriptClass(String className, String relPath) {
        scriptClasses.put(className, relPath);
    }

    /** 反查脚本类名对应的相对路径,未登记返回空串(事件回调内注册监听器时使用)。 */
    public static String lookupScriptPath(String className) {
        return scriptClasses.getOrDefault(className, "");
    }

    /** 启用状态:globals 中 groovier.enabled.<path> 显式为 false 即禁用,缺省启用。 */
    public boolean isEnabled(String relPath) {
        Object v = GlobalManager.INSTANCE.get(ENABLED_PREFIX + relPath);
        return !(v instanceof Boolean b && !b);
    }

    /** 将命令参数解析为脚本相对路径(精确匹配,或按文件名后缀匹配)。返回 null 表示无匹配。 */
    public String resolveScript(String name) {
        if (scripts.containsKey(name)) return name;
        for (String path : scripts.keySet()) {
            if (path.equals(name) || path.endsWith("/" + name)) {
                return path;
            }
        }
        return null;
    }

    /** 解析脚本名,找不到时返回含候选列表的错误信息。 */
    public String resolveScriptOrError(String name) {
        String path = resolveScript(name);
        if (path != null) return path;
        StringBuilder sb = new StringBuilder("Script not found: ").append(name).append(". Available: ");
        for (String p : scripts.keySet()) sb.append(p).append(" ");
        return sb.toString().trim();
    }

    /** 层2并发保护:开始后台重载(已在重载中返回 false,调用方给出反馈)。 */
    public boolean tryBeginReload() {
        return reloadInProgress.compareAndSet(false, true);
    }

    /** 层2并发保护:结束后台重载(apply 完成或 prepare 异常后调用)。 */
    public void endReload() {
        reloadInProgress.set(false);
    }

    private void onGlobalChanged() {
        if (inChange) return;
        inChange = true;
        try {
            for (ScriptState state : scripts.values()) {
                boolean enabled = isEnabled(state.relPath);
                if (enabled != state.enabled) {
                    state.enabled = enabled;
                    if (enabled) {
                        GroovyLog.INSTANCE.info("Script {} enabled, running.", state.relPath);
                        runScript(state.relPath);
                    } else {
                        GroovierEventManager.INSTANCE.unregisterScript(state.relPath);
                        com.bluesky.groovier.hooks.GroovierHooks.unregisterScript(state.relPath);
                        GroovyLog.INSTANCE.info("Script {} disabled, listeners unregistered.", state.relPath);
                    }
                }
            }
        } finally {
            inChange = false;
        }
    }

    /** G2b:锚定沙箱 MetaClass 创建句柄——被脚本换掉则恢复,保证黑名单类运行时拦截不失效。 */
    private static void ensureSandboxHandle() {
        MetaClassRegistry registry = GroovySystem.getMetaClassRegistry();
        if (!(registry.getMetaClassCreationHandler() instanceof GrSMetaClassCreationHandle)) {
            GroovyLog.INSTANCE.warn("Groovier sandbox MetaClass creation handle was replaced, restoring.");
            registry.setMetaClassCreationHandle(GrSMetaClassCreationHandle.INSTANCE);
        }
    }

    public List<ScriptState> getScripts() {
        // M8(b) 换 ConcurrentHashMap 后无插入序,按路径排序保持 /groovier scripts 展示确定性
        List<ScriptState> list = new ArrayList<>(scripts.values());
        list.sort(Comparator.comparing(s -> s.relPath));
        return list;
    }

    public static class ScriptState {
        public final String relPath;
        public boolean enabled;
        // 第三态:最近一次编译/执行失败(enabled 但 failed)。reload 或 globals 再启用可重试
        public boolean failed;

        ScriptState(String relPath, boolean enabled) {
            this.relPath = relPath;
            this.enabled = enabled;
            this.failed = false;
        }
    }

    /** 单个脚本的字节码缓存条目(层1):文件内容哈希 + 编译产出的全部类(类名 -> 字节码)。 */
    private static final class CachedScript {
        final String hash;
        final Map<String, byte[]> classes;

        CachedScript(String hash, Map<String, byte[]> classes) {
            this.hash = hash;
            this.classes = classes;
        }
    }

    /** prepare 产物:apply(server thread)据此一次性切换;后台线程不触碰事件总线与脚本执行。 */
    public static class ReloadResult {
        final GroovierClassLoader loader;
        final Binding template;
        final File root;
        final Map<String, ScriptState> states = new LinkedHashMap<>();  // 全部扫描脚本 + 启用状态
        final Map<String, Class<?>> successful = new LinkedHashMap<>(); // 成功编译/加载的 relPath -> 主类
        final List<String> failed = new ArrayList<>();                  // 失败脚本(编译失败或执行失败)
        int disabledCount;
        long prepareMillis;
        long applyMillis;

        ReloadResult(GroovierClassLoader loader, Binding template, File root) {
            this.loader = loader;
            this.template = template;
            this.root = root;
        }
    }
}
