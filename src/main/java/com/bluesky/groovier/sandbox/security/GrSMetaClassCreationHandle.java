package com.bluesky.groovier.sandbox.security;

import com.bluesky.groovier.engine.GroovierClassLoader;

import groovy.lang.MetaClass;
import groovy.lang.MetaClassRegistry;

/**
 * 定制 Groovy MetaClass 创建:黑名单类/包 → 返回空 MetaClass(调用即抛异常)。
 * 注入到全局 MetaClassRegistry。
 *
 * <p>作用域(6.4 机制发现,2026-08-30):守卫按类加载器划界——
 * <ul>
 *   <li>JDK 类(null 加载器):始终守卫(脚本逃逸面,不可豁免);</li>
 *   <li>GroovierClassLoader 体系内定义的类(脚本及其辅助闭包):守卫;</li>
 *   <li>游戏类加载器定义的可信类(含 6.4 整类覆盖复刻类):放行——否则覆盖类的
 *       动态分派会被全局句柄误伤(实证:refer/ReferClasstree 复刻类对
 *       sun.nio.fs.WindowsPath 的合法动态调用被黑名单拦截)。</li>
 * </ul>
 */
public class GrSMetaClassCreationHandle extends MetaClassRegistry.MetaClassCreationHandle {

    public static final GrSMetaClassCreationHandle INSTANCE = new GrSMetaClassCreationHandle();

    private GrSMetaClassCreationHandle() {}

    @SuppressWarnings("rawtypes")
    @Override
    protected MetaClass createNormalMetaClass(Class theClass, MetaClassRegistry registry) {
        if (isSandboxWorld(theClass) && !GroovySecurityManager.INSTANCE.isValid(theClass)) {
            return new BlackListedMetaClass(theClass);
        }
        return super.createNormalMetaClass(theClass, registry);
    }

    private static boolean isSandboxWorld(Class theClass) {
        ClassLoader cl = theClass.getClassLoader();
        if (cl == null) {
            // JDK 类始终守卫(Runtime/ProcessBuilder 等逃逸工具均为 null 加载器)
            return true;
        }
        for (; cl != null; cl = cl.getParent()) {
            if (cl instanceof GroovierClassLoader) {
                return true;
            }
        }
        return false;
    }
}
