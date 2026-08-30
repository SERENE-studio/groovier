package com.bluesky.groovier;

import com.bluesky.groovier.api.GroovierLogFile;
import com.bluesky.groovier.command.GroovierCommand;
import com.bluesky.groovier.sandbox.GroovierSandbox;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(Groovier.MODID)
public class Groovier {

    public static final String MODID = "groovier";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GroovierSandbox sandbox;

    public Groovier(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Groovier mod initializing");
        // 独立日志文件:脚本输出与 mod 自身日志写入 logs/groovier.log(类似 KubeJS 的 logs/kubejs)
        GroovierLogFile.setup();
        sandbox = new GroovierSandbox();
        // globals 可选持久化:恢复 local/globals.json 快照 + 周期落盘(必须在脚本执行前恢复)
        com.bluesky.groovier.api.GlobalPersistence.install();
        // 6.4 整类覆盖绑定:最早 GAME 层入口编译 override 源并落盘(rebind 内部 fail-safe,
        // 失败仅降级日志,不影响启动;核心侧 coprocessor 回调按文件产物整类替换)
        com.bluesky.groovier.override.OverrideManager.rebind();
        // 仅服务端:服务器启动(世界加载前)执行脚本
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        // 注册 /groovier 命令组
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarting(ServerStartingEvent event) {
        sandbox.run();
        // 6.5:后台反编译导出 refer 产物(groovier-refer.txt 命中类已在启动期捕获;
        // 懒加载类可在运行期 /gvr refer 重跑)。
        // 防 NCDFE:dev 环境 vineflower 可能不在 GAME 层类路径,类链接失败不能炸 server。
        try {
            com.bluesky.groovier.refer.ReferExporter.runAsync();
        } catch (Throwable t) {
            LOGGER.error("Groovier refer export trigger failed (refer channel disabled this session)", t);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GroovierCommand.register(event.getDispatcher());
    }

    public static GroovierSandbox getSandbox() {
        if (sandbox == null) {
            throw new IllegalStateException("Groovier is not yet loaded!");
        }
        return sandbox;
    }
}
