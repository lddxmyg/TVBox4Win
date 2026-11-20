package com.github.tvbox.osc.server;

import android.content.Context;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 桩类：原版 RemoteServer 的最小复刻。
 */
public class RemoteServer {

    public static int serverPort = 9978;

    public static String getLocalIPAddress(Context ctx) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<java.net.InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (java.net.InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }
}
