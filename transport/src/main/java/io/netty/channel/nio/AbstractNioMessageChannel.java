/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.*;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
        return allocHandle.continueReading();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        /**
         * 简单的list {@link AbstractNioMessageChannel#doReadMessages(List)}方法将读到的连接放入到这个list中
         */
        private final List<Object> readBuf = new ArrayList<Object>();

        /**
         *     - 此时客户端向服务端发起Connect连接请求 NioServerSocketChannel会收到就绪事件类型16的Accept
         *         - NioServerSocketChannel读取连接实现在NioMessageUnsafe中
         *         - NioMessageUnsafe负责接收NioSocketChannel连接
         *         - 调用Jdk底层的accept接收客户端连接
         *         - 将accept结果封装成NioSocketChannel向pipeline传播(pipeline中有 head-bossHandler-ServerBootstrapAcceptor-tail)
         *         - 触发ServerBootstrapAcceptor回调
         */
        @Override
        public void read() {
            assert eventLoop().inEventLoop(); // IO操作(Channel上的读写)只能由注册的复用器所在的线程 也就是绑定的唯一的NioEventLoop线程执行
            /**
             * 给Channel的配置参数 最终体现在OS的Socket上
             *     - 通过ServerBootstrap#config传递的NioServerSocketChannel的配置信息
             */
            final ChannelConfig config = config();
            /**
             * 每个Channel中都维护了一个pipeline
             *     - NioServerSocket收到客户端连接 触发自己的Accept接收连接状态 读取连接信息
             */
            final ChannelPipeline pipeline = pipeline();
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle(); // 接收对端数据时 ByteBuf的分配策略(基于历史数据动态调整大小 避免太大发生空间浪费 避免太小造成频繁扩容)
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        /**
                         * NioServerSocketChannel接收客户端NioSocketChannel连接
                         *     - Jdk底层系统调用accept
                         *     - 将服务端fork出来的Socket封装成Jdk的SocketChannel
                         *     - Netty将Jdk的SocketChannel封装成NioSocketChannel
                         *     - 将NioServerSocketChannel和accept结果NioSocketChannel一起封装到ByteBuf中
                         */
                        int localRead = AbstractNioMessageChannel.this.doReadMessages(readBuf);
                        if (localRead == 0) break;
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        allocHandle.incMessagesRead(localRead); // 读到的连接数计数
                    } while (continueReading(allocHandle)); // 连接数是否超过最大值
                } catch (Throwable t) {
                    exception = t;
                }
                // 遍历每一条客户端连接
                int size = readBuf.size();
                for (int i = 0; i < size; i++) {
                    readPending = false;
                    /**
                     * 向NioServerSocketChannel的pipeline传播ChannelRead事件
                     * 此时pipeline中3个handler
                     *     - head
                     *     - ServerBootstrapAcceptor
                     *     - tail
                     * ServerBootstrap将回调方法处理服务端收到的客户端连接
                     * 对于ServerBootstrap的回调方法而言 收到的参数就是这儿的readBuf.get(...)内容 也就是每一条连接信息(ServerSocket, accept后fork出来的Socket)
                     */
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                readBuf.clear();
                allocHandle.readComplete();
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    closed = closeOnReadError(exception);

                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        int maxMessagesPerWrite = maxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    maxMessagesPerWrite--;
                    in.remove();
                } else {
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    maxMessagesPerWrite--;
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
        if (in.isEmpty()) {
            // Wrote all messages.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // Did not write all messages.
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(interestOps | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
