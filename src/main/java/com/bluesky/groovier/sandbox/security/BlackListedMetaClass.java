package com.bluesky.groovier.sandbox.security;

import groovy.lang.GroovySystem;
import groovy.lang.MetaClassImpl;

/**
 * 黑名单类的空 MetaClass:所有方法调用/属性访问/构造均抛沙箱安全异常。
 */
public class BlackListedMetaClass extends MetaClassImpl {

    public BlackListedMetaClass(Class<?> theClass) {
        super(GroovySystem.getMetaClassRegistry(), theClass);
    }

    @Override
    public Object invokeMethod(Object object, String methodName, Object[] arguments) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public Object invokeMethod(Class sender, Object object, String methodName, Object[] arguments, boolean isCallToSuper, boolean fromInsideClass) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public Object invokeConstructor(Object[] arguments) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public Object getProperty(Object object, String property) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public void setProperty(Object object, String property, Object newValue) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    // 备注①:补齐 MetaClass 接口多参变体,防止内部调用链(类内属性访问/super 语义)回落真实逻辑
    @Override
    public Object getProperty(Class sender, Object object, String property, boolean useSuper, boolean fromInsideClass) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }

    @Override
    public void setProperty(Class sender, Object object, String property, Object newValue, boolean useSuper, boolean fromInsideClass) {
        throw new SandboxSecurityException("Class " + getTheClass().getName() + " is blacklisted in the Groovier sandbox!");
    }
}
