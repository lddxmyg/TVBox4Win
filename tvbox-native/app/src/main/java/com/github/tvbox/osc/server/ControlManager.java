package com.github.tvbox.osc.server;

/**
 * 桩类：原版 ControlManager 的最小复刻。
 * 原版是 NanoHTTPD 本地服务（proxy/file 推流）；这里先返回固定地址，
 * file:// 与 clan:// 模块在无本地服务时自然失败回退，不影响主流程。
 */
public class ControlManager {

    private static final ControlManager instance = new ControlManager();

    public static ControlManager get() {
        return instance;
    }

    public String getAddress(boolean local) {
        return "http://" + (local ? "127.0.0.1" : "127.0.0.1") + ":" + RemoteServer.serverPort;
    }
}
