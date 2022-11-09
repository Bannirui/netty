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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this); // 创建ServerBootstrapConfig实例 对象中bootstrap属性持有ServerBootstrap的实例 可以通过config获取到bootstrap所有的属性

    // 关注成功连接的请求
    private volatile EventLoopGroup childGroup;

    /**
     * 作用在workerGroup线程组
     * 只监听已经成功连接服务端的那些客户端{@link Channel}
     */
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) { // 设置线程池
        super.group(parentGroup); // boss线程池组交给父类
        if (this.childGroup != null) throw new IllegalStateException("childGroup set already");
        this.childGroup = childGroup; // worker线程池组初始化childGroup属性
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    /**
     * 服务端特有方法 添加进来的{@link ChannelHandler}处理器只作用在workerGroup线程组
     * 处理器只处理连接进服务端的客户端{@link Channel}
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = childHandler;
        return this;
    }

    @Override
    void init(Channel channel) { // NioServerSocketChannel实例
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());

        ChannelPipeline p = channel.pipeline(); // channel内部的pipeline实例 在创建NioServerSocketChannel的时候一起创建了pipeline实例

        final EventLoopGroup currentChildGroup = childGroup; // workerGroup
        final ChannelHandler currentChildHandler = childHandler; // workerHandler
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);

        /**
         * 往ServerSocketChannel的pipeline中添加一个handler 这个handler是ChannelInitializer的实例 该处涉及到pipeline中的辅助类ChannelInitializer 它本身也是一个handler(Inbound类型) 它的作用仅仅是辅助其他的handler加入到pipeline中
         * ChannelInitializer的initChannel方法触发时机是在Channel绑定到EventLoop线程之后* 那么到时候会发生
         *     - 添加一个ServerBootstrap指定的bossHandler(也可能没指定)
         *     - 唤醒EventLoop线程让它添加ServerBootstrapAcceptor这个handler
         *
         * 下面这段代码的目的在于一定会唤醒EventLoop线程 并一定添加了ServerBootstrapAcceptor
         */
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) { // ChannelInitializer的这个方法会在Channel绑定到EventLoop线程之后被回调
                final ChannelPipeline pipeline = ch.pipeline(); // NioServerSocketChannel的pipeline
                ChannelHandler handler = config.handler(); // 这个handler是在ServerBootstrap::handler()方法中指定的workerHandler
                if (handler != null) pipeline.addLast(handler); // 将bossHandler添加到NioServerSocket的pipeline中

                ch.eventLoop().execute(new Runnable() { // 往NioEventLoop线程添加一个任务 boss线程会执行这个任务 就是给Channel指定一个处理器 处理器的功能是接收客户端请求
                    @Override
                    public void run() {
                        pipeline.addLast(
                                // 添加一个handler到pipeline中 ServerBootstrapAcceptor这个handler目的是用来接收客户端请求的
                                new ServerBootstrapAcceptor(
                                    ch, // NioServerSocketChannel
                                    currentChildGroup, // workerGroup
                                    currentChildHandler, // workerHandler
                                    currentChildOptions,
                                    currentChildAttrs
                                )
                        );
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup; // workerGroup
        private final ChannelHandler childHandler; // workerHandler
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, // NioServerSocketChannel
                EventLoopGroup childGroup, // workerGroup
                ChannelHandler childHandler, // worekrHandler
                Entry<ChannelOption<?>, Object>[] childOptions,
                Entry<AttributeKey<?>, Object>[] childAttrs
        ) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) { // 服务端收到连接或者收到消息 对于服务端视角而言 都是收到了可读事件
            /**
             * msg参数就是在{@link AbstractNioMessageChannel#read()}方法中调用{@code pipeline.fireChannelRead(readBuf.get(i))}方法的入参NioSocketChannel
             */
            final Channel child = (Channel) msg;

            child.pipeline().addLast(this.childHandler);

            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            try {
                this.childGroup.register(child) // 在workerGroup线程组中轮询出一个NioEventLoop线程跟child这个Channel绑定
                        .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
