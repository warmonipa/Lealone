/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.lealone.common.concurrent.ConcurrentUtils;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.net.nio.NioWritableChannel;

//只负责接收新的TCP连接
//TODO 1.支持SSL 2.支持配置参数
public class AsyncConnectionAccepter extends NetServerBase {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConnectionAccepter.class);
    private ServerSocketChannel serverChannel;

    @Override
    public synchronized void start() {
        if (isStarted())
            return;
        logger.info("Starting connection accepter");
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(getHost(), getPort()));
            serverChannel.configureBlocking(true);
            super.start();
            String name = getName() + "Accepter-" + getPort();
            if (isRunInMainThread()) {
                Thread t = Thread.currentThread();
                if (t.getName().equals("main"))
                    t.setName(name);
            } else {
                ConcurrentUtils.submitTask(name, isDaemon(), () -> {
                    AsyncConnectionAccepter.this.run();
                });
            }
        } catch (Exception e) {
            checkBindException(e, "Failed to start connection accepter");
        }
    }

    @Override
    public synchronized void stop() {
        if (isStopped())
            return;
        logger.info("Stopping connection accepter");
        super.stop();
        if (serverChannel != null) {
            try {
                serverChannel.close();
                serverChannel = null;
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public Runnable getRunnable() {
        return () -> {
            AsyncConnectionAccepter.this.run();
        };
    }

    private void run() {
        while (!isStopped()) {
            accept();
        }
    }

    private void accept() {
        SocketChannel channel = null;
        AsyncConnection conn = null;
        try {
            channel = serverChannel.accept();
            channel.configureBlocking(false);
            NioWritableChannel writableChannel = new NioWritableChannel(channel, null);
            conn = createConnection(writableChannel);
        } catch (Throwable e) {
            if (conn != null) {
                removeConnection(conn);
            }
            closeChannel(channel);
            // 按Ctrl+C退出时accept可能抛出异常，此时就不需要记录日志了
            if (!isStopped()) {
                logger.warn(getName() + " failed to accept connection", e);
            }
        }
    }

    public static void closeChannel(SocketChannel channel) {
        if (channel != null) {
            Socket socket = channel.socket();
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
            }
            try {
                channel.close();
            } catch (Throwable e) {
            }
        }
    }
}