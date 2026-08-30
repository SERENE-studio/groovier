package com.bluesky.groovier.sandbox.security;

/**
 * 沙箱安全异常:脚本触犯黑名单时抛出。
 */
public class SandboxSecurityException extends SecurityException {

    public SandboxSecurityException(String message) {
        super(message);
    }
}
