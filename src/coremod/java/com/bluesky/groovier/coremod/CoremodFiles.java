package com.bluesky.groovier.coremod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import cpw.mods.modlauncher.api.IEnvironment;

/**
 * coremod 侧共享小工具:GAMEDIR 定位与原子写文件(SERVICE 层零第三方依赖)。
 *
 * GAMEDIR 定位:优先 IEnvironment 注入键;onLoad 时该键可能尚未注入,回退
 * FMLPaths(经反射读取,避免对 fml-loader 的硬类依赖 —— ModDirTransformerDiscoverer
 * 拉取本 service 前 FMLPaths 已完成初始化)。不再回退 user.dir:进程 cwd 与
 * GAMEDIR 不一致时(生产环境自定义启动目录),coremod 写 / GAME 层读会错位,
 * 宁可禁用产物并告警(安全侧),也不要写错位置。
 *
 * 原子写:同目录 temp + ATOMIC_MOVE 落盘,"写后即被另一层读取"的文件
 * (report.json、pre/*.class 等)读端不会看到半截内容。
 */
final class CoremodFiles {

    private CoremodFiles() {}

    static Path gameDir(IEnvironment environment) {
        // getProperty 返回 Optional<Path>(modlauncher 11 API)
        Path dir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(null);
        if (dir != null) {
            return dir;
        }
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gamedir = fmlPaths.getField("GAMEDIR").get(null);
            return (Path) fmlPaths.getMethod("get").invoke(gamedir);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    static void atomicWrite(Path file, String content) throws IOException {
        atomicWrite(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
