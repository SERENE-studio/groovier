package com.bluesky.groovier.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写文件工具:同目录 temp 文件 + {@code Files.move(..., ATOMIC_MOVE)} 落盘。
 *
 * <p>用于"写后即被另一层读取"的产物(pin.txt、surgery.txt/patch.class 等):
 * 读端要么看到旧内容、要么看到完整新内容,不会读到半截。
 * temp 文件与目标同目录,保证同卷原子 move 可用;文件系统不支持原子 move 时
 * 退化为 REPLACE_EXISTING 常规 move。
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void writeString(Path file, String content) throws IOException {
        write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path file, byte[] bytes) throws IOException {
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
