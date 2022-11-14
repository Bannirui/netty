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

import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    volatile EventLoopGroup group;

    /**
     * Channel创建工厂->内部阈维护了xxxChannel的默认构造器->触发时机->{@link AbstractBootstrap#initAndRegister()}->newInstance()方式创建xxxChannel实例
     *
     * {@link NioServerSocketChannel#NioServerSocketChannel()}->创建服务端Chanel实例
     * {@link NioSocketChannel#NioSocketChannel()}->创建客户端Channel实例
     *
     * 触发时机分别为:
     * 服务端{@link ServerBootstrap#bind()}
     * 客户端{@link Bootstrap#connect()}
     */
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;
    private volatile SocketAddress localAddress;

    // The order in which ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    /**
     * 作用在bossGroup 监听{@link Channel}的状态变更和动作
     */
    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     */
    public B group(EventLoopGroup group) {
        if (this.group != null) throw new IllegalStateException("group set already");
        this.group = group; // group属性赋值为EventLoopGroup实例 服务端ServerBootstrap传进来的是bossGroup 客户端Bootstrap传进来的是group
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     */
    /**
     * 创建channelFactory->等到特定时机->创建SocketChannel
     *
     * 形参指向的类是
     * {@link io.netty.channel.socket.nio.NioServerSocketChannel} 无参构造器是{@link NioServerSocketChannel#NioServerSocketChannel()}
     * {@link io.netty.channel.socket.nio.NioSocketChannel} 无参构造器是{@link NioSocketChannel#NioSocketChannel()}
     *
     * 其作用就是提供SocketChannel的无参构造器 生成ChannelFactory
     */
    public B channel(Class<? extends C> channelClass) { // 指定Channel类型->根据Channel特定实现的无参构造方法->反射创建Channel实例
        return this.channelFactory(new ReflectiveChannelFactory<C>(channelClass)); // NioServerSocket的class对象
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) { // channelFactory的setter
        if (this.channelFactory != null) throw new IllegalStateException("channelFactory set already");
        /**
         * {@link io.netty.channel.ChannelFactory}本质就是一个Channel工厂->阈维护了一个无参构造器->通过newInstance()创建Channel实例
         *
         * 两个无参构造器:
         * {@link NioServerSocketChannel#NioServerSocketChannel()}->创建服务端Channel实例
         * {@link NioSocketChannel#NioSocketChannel()}->创建客户端Channel实例
         */
        this.channelFactory = channelFactory; // 设置channelFactory属性 将ReflectiveChannelFactory实例赋值给该属性
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return this.channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     */
    public B validate() {
        if (this.group == null) throw new IllegalStateException("group not set");
        if (this.channelFactory == null) throw new IllegalStateException("channel or channelFactory not set");
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    /**
     * 异步非阻塞方法
     *     - 异步 比如main线程发起调用 真正的bind结果main线程并不是直接去取 而是将来真正bind成功的线程将结果回调
     *     - 非阻塞 比如main线程发起调用 最后真正执行bind操作的是其他线程(Nio线程 NioEventLoop线程)
     */
    public ChannelFuture bind(int inetPort) {
        return this.bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        this.validate();
        return this.doBind(localAddress);
    }

    private ChannelFuture doBind(final SocketAddress localAddress) {
        /**
         * 完成几个步骤
         *     - Netty的Channel创建(本质是通过反射方式 NioServerSocketChannel和NioSocketChannel的无参构造器)
         *     - Channel中维护几个重要组件
         *         - unsafe 操作读写
         *         - eventLoop Channel绑定的NioEventLoop Channel注册到这个NioEventLoop上的复用器Selector
         *         - pipeline 维护handler
         *     - 绑定关联Netty的Channel和NioEventLoop线程关系
         *     - Jdk的Channel注册到NioEventLoop的复用器Selector上
         *     - 注册复用器成功后发布事件
         *         - handlersAdded事件 -> 会触发ChannelInitializer的方法回调
         *         - active事件
         *         - ...
         */
        final ChannelFuture regFuture = this.initAndRegister();
        final Channel channel = regFuture.channel(); // 获取channel NioServerSocketChannel的实例
        if (regFuture.cause() != null) return regFuture;

        /**
         * Channel注册复用器是NioEventLoop线程的异步任务
         * 这里是为了保证注册复用器的操作先于bind
         *     - 复用器注册完触发ChannelInitializer方法回调 向pipeline中添加必要的handler 保证后续发生读写时 Channel都能依赖上完整的handler链
         *     - 前一个复用器注册时异步执行
         *         - 如果已经复用器注册已经完成 pipeline中handler已经初始化好 向NioEventLoop提交任务让它执行bind
         *         - 如果复用器注册还没完成 说明这个任务还在NioEventLoop的任务队列taskQueue中 这个时候再向NioEventLoop提交一个异步任务 这两个任务的顺序通过任务队列保证了相对顺序
         */
        if (regFuture.isDone()) {
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise); // 此时Channel已经跟NioEventLoop线程绑定了 给boss线程添加个任务 让boss线程执行bind操作 这个地方也是真正启动boss线程的时机
            return promise;
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() { // nio线程注册复用器是异步操作 给这个操作加个回调 等nio线程完成注册复用器之后 让它执行doBind0(...)
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();

                        doBind0(regFuture, channel, localAddress, promise); // NioEventLoop线程执行bind(headHandler处理器真正处理) bind完成后通过向NioEventLoop提交异步任务方式 让NioEventLoop发布一个NioServerSocketChannel的active事件
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 完成几个步骤
     *     - Netty的Channel创建(本质是通过反射方式 NioServerSocketChannel和NioSocketChannel的无参构造器)
     *     - Channel中维护几个重要组件
     *         - unsafe 操作读写
     *         - eventLoop Channel绑定的NioEventLoop Channel注册到这个NioEventLoop上的复用器Selector
     *         - pipeline 维护handler
     *     - 绑定关联Netty的Channel和NioEventLoop线程关系
     *     - Jdk的Channel注册到NioEventLoop的复用器Selector上
     *     - 注册复用器成功后发布事件
     *         - handlersAdded事件 -> 会触发ChannelInitializer的方法回调
     *         - active事件
     *         - ...
     */
    final ChannelFuture initAndRegister() {
        /**
         * 具体实现是{@link NioServerSocketChannel}的实例或者{@link NioSocketChannel}的实例
         */
        Channel channel = null;
        try {
            /**
             * {@link io.netty.channel.ChannelFactory#newChannel()}->{@link ReflectiveChannelFactory#newChannel()}->{@link NioServerSocketChannel}或{@link NioSocketChannel}的无参构造器
             *
             * 两个无参构造器分别是:
             * {@link NioServerSocketChannel#NioServerSocketChannel()}
             * {@link NioSocketChannel#NioSocketChannel()}
             *
             * <p><h3>netty的channel实例化</h3></p>
             * <ul>
             *     <li>创建jdk的ServerSocketChannel和SocketChannel封装成Netty中的NioServerSocketChannel和NioSocketChannel</li>
             *     <li>给每个netty channel分配属性</li>
             *     <ul>
             *         <li>id</li>
             *         <li>unsafe实例</li>
             *         <li>pipeline实例 pipeline中有两个handler(head和tail 它们不是哑节点)</li>
             *     </ul>
             *     <li>netty的NioServerSocketChannel组合jdk的ServerSocketChannel</li>
             *     <li>NioServerSocketChannel关注OP_ACCEPT连接事件</li>
             *     <li>NioSocketChannel关注OP_READ可读事件</li>
             *     <li>设置jdk ServerSocketChannel的非阻塞模式</li>
             * </ul>
             *
             */
            channel = this.channelFactory.newChannel(); // 通过Factory触发NioServerSocketChannel和SocketChannel的反射创建实例
            /**
             *  - pipeline中添加个ChannelInitializer
             *      - 等待NioServerSocketChannel注册复用器后被回调
             *          - 添加workerHandler
             *          - 提交异步任务
             *              - 在pipeline中添加ServerBootstrapAcceptor
             */
            this.init(channel);
        } catch (Throwable t) {
            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly();
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        /**
         * 至此NioEventLoop线程还没启动
         * 在register(...)方法之前 已经完成的工作
         *
         *     - channel实例
         *         - pipeline实例化
         *             - pipeline中添加了{@link ChannelInitializer}辅助类的实例 而这个辅助类的触发时机是在Channel跟EventLoop线程绑定之后
         *         - unsafe实例
         *         - 设置了jdk channel的非阻塞模式
         *     - bossGroup中仅仅实例化了特定数量的EventLoop 但是此时线程并没有被真正创建
         *     - Channel跟EventLoop没有关联
         *
         * <pre>{@code this.config().group()}返回的是{@link io.netty.channel.nio.NioEventLoopGroup}实例</pre>
         * 对于ServerBootstrap而言是bossGroup线程组
         * 对于Bootstrap而言只有一个group线程组
         *
         * 线程组的register(...)方法就是从group中轮询出来一个NioEventLoop线程执行register(...)方法 Channel跟NioEventLoop关联起来并注册到NioEventLoop的Selector上
         *     - 注册复用器结束后 NioEventLoop线程发布一些事件让关注的handler执行
         *         - handlersAdd(...)事件 触发
         *         - ...
         *             - 添加ServerBootstrapAcceptor处理来自客户端的连接
         *             - NioEventLoop线程循环
         *
         * 并且一旦Channel一旦跟EventLoop绑定 以后Channel的所有事件都由这个EventLoop线程处理
         * 所谓的注册指的是将Java的Channel注册到复用器Selector上
         *     - 逻辑绑定映射关系 Netty Channel跟NioEventLoop关系绑定
         *     - 物理注册复用器
         *         - 通过向NioEventLoop提交任务方式启动NioEventLoop线程
         *         - NioEventLoop线程将Jdk的Channel注册到Selector复用器上
         *             - Socket注册复用器 不关注事件
         *             - 触发事件 让pipeline中的handler关注响应(此刻pipeline中有head ChannelInitializer实例 tail)
         *                 - 发布handlerAdd事件 触发ChannelInitializer方法执行
         *                 - 发布register事件
         */
        ChannelFuture regFuture = this
                .config()
                .group() // {#link ServerBootstrap#group()}或者{@link Bootstrap#group()}传进去的 比如在服务端就是boss线程组 客户端只有一个group
                .register(channel);
        if (regFuture.cause() != null) { // 在register过程中发生异常
            if (channel.isRegistered()) channel.close();
            else channel.unsafe().closeForcibly();
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.
        /**
         * // 执行到这 说明后续可以进行NioSocketChannel::connect()方法或者NioServerSocketChannel::bind()方法
         * 两种情况
         *     -1 register动作是在eventLoop中发起 那么到这里的时候 register一定已经完成了
         *     -2 如果register任务已经提交到eventLoop中 也就是进到了eventLoop中的taskQueue中 由于后续的connect和bind方法也会进入到同一个eventLoop的taskQueue中 所以一定会先执行register成功 再执行connect和bind方法
         */
        return regFuture;
    }

    /**
     * - NioServerSocketChannel->pipeline中添加个ChannelInitializer
     *     - 等待NioServerSocketChannel注册复用器后被回调
     *         - 添加workerHandler
     *         - 提交异步任务
     *             - 在pipeline中添加ServerBootstrapAcceptor
     * - NioSocketChannel->bootStrap指定的workerHandler添加到pipeline中
     */
    abstract void init(Channel channel) throws Exception;

    /**
     * Channel中发生的读写数据操作都会按照InBound和OutBound类型交给pipeline 让关注的handler执行
     */
    private static void doBind0(final ChannelFuture regFuture, final Channel channel, final SocketAddress localAddress, final ChannelPromise promise) {
        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        // 提交异步任务让NioEventLoop线程执行bind 此时pipeline的handler(head workerHandler tail) bind操作属于OutBound类型 因此从tail往前找handler headHandler负责真正执行bind
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() { // NioEventLoop执行真正的bind操作 添加监听器处理异步操作结果 NioEventLoop执行bind结束后 它自己知道bind结果 处理ChannelFutureListener.CLOSE_ON_FAILURE的逻辑
                if (regFuture.isSuccess()) channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                else promise.setFailure(regFuture.cause());
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    /**
     * 服务端{@link NioServerSocketChannel}和客户端{@link NioSocketChannel}都提供了{@link ChannelHandler}的设置
     * 用来监听{@link Channel}的各种状态改变和动作 包括连接 绑定 接收消息
     *
     * 服务端{@link NioServerSocketChannel}还额外提供了#childHandler()方法 这个方法设置的{@link ChannelHandler}是用来监听已经连接进来的客户端{@link Channel}的状态和动作
     *
     * 最大的区别:
     * 1, #handler()方法添加的处理器在初始化时执行 #childHandler()方法添加的处理器在客户端成功connect进服务端才执行
     * 2, #handler()方法添加的处理器在bossGroup线程组生效 #childHandler()方法添加的处理器在服务端的workerGroup线程组生效
     *
     * 也就意味着:
     * 对于服务端而言 处理器作用域只在bossGroup线程组 只负责处理服务端新请求
     * 对于客户端而言 没有workerGroup
     */
    public B handler(ChannelHandler handler) {
        this.handler = handler;
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    /**
     * <p>返回{@link AbstractBootstrap}的group属性</p>
     * <ul>
     *     <li>{@link ServerBootstrap}返回的是bossGroup实例{@link io.netty.channel.nio.NioEventLoopGroup}</li>
     *     <li>{@link Bootstrap}返回的是group实例{@link io.netty.channel.nio.NioEventLoopGroup}</li>
     * </ul>
     */
    @Deprecated
    public final EventLoopGroup group() {
        return this.group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        return newOptionsArray(options);
    }

    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        return newAttributesArray(attrs0());
    }

    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
