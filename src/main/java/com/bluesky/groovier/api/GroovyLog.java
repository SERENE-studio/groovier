package com.bluesky.groovier.api;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 脚本全局日志绑定(脚本内绑定名为 Log)。供脚本直接调用 Log.info(...) 等。
 */
public class GroovyLog {

    public static final GroovyLog INSTANCE = new GroovyLog();

    private final Logger logger = LogUtils.getLogger();

    private GroovyLog() {}

    public void info(String format, Object... args) {
        logger.info(format, args);
    }

    public void warn(String format, Object... args) {
        logger.warn(format, args);
    }

    public void error(String format, Object... args) {
        logger.error(format, args);
    }
}
