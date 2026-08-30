package com.bluesky.groovier.api;

import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * 独立日志文件配置:为 com.bluesky.groovier 包的所有日志(脚本输出 + mod 自身)单独写入 logs/groovier.log,
 * 类似 KubeJS 的 logs/kubejs。路径以 GAMEDIR 为基准(进程 cwd 在生产环境不一定是游戏目录)。
 */
public class GroovierLogFile {

    private static final String LOGGER_NAME = "com.bluesky.groovier";
    private static final String FILE_NAME = FMLPaths.GAMEDIR.get()
            .resolve("logs").resolve("groovier.log").toString();

    private GroovierLogFile() {}

    public static void setup() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();

        FileAppender appender = FileAppender.newBuilder()
                .setName("GroovierFile")
                .withFileName(FILE_NAME)
                .withAppend(false) // 每次游戏启动覆盖清空 groovier.log(单次会话内不断追加)
                .setLayout(PatternLayout.newBuilder()
                        .withPattern("[%d{HH:mm:ss}] [%level] %msg%n")
                        .build())
                .setConfiguration(config)
                .build();
        appender.start();
        config.addAppender(appender);

        LoggerConfig loggerConfig = config.getLoggerConfig(LOGGER_NAME);
        if (!loggerConfig.getName().equals(LOGGER_NAME)) {
            // 尚无该包的显式 LoggerConfig,创建之(继承 root 的 additivity)
            loggerConfig = new LoggerConfig(LOGGER_NAME, Level.INFO, true);
            config.addLogger(LOGGER_NAME, loggerConfig);
        }
        loggerConfig.addAppender(appender, null, null);
        context.updateLoggers();
    }
}
