package com.scholarclaw.common.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP 可达性探针：只验证目标主机端口能建立连接。
 *
 * <p><b>能力边界（不得夸大）：</b>它证明网络路径通、进程在监听，**不证明**凭据正确、
 * 数据库可读写或队列可用。真正基于连接池的探测在 TASK-201 引入 JDBC/Redis/MQ 客户端后替换，
 * 见 {@link HealthProbe} 的说明。
 */
public class TcpReachabilityProbe implements HealthProbe {

    private final String name;
    private final String host;
    private final int port;
    private final int timeoutMillis;

    public TcpReachabilityProbe(String name, String host, int port, int timeoutMillis) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return Kind.DEPENDENCY;
    }

    @Override
    public boolean isUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
