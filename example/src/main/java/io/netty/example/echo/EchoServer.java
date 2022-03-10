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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.net.SocketAddress;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    /**
     * <p><h3>Netty启动流程</h3></p>
     *
     * <p><h4>服务端</h4></p>
     * <ul>
     *     <li>1 {@link ServerBootstrap#ServerBootstrap()}创建启动引导实例</li>
     *     <li>2 {@link ServerBootstrap#group(EventLoopGroup, EventLoopGroup)}初始化boss和worker线程池</li>
     *     <li>3 {@link ServerBootstrap#channel(Class)}传入{@link NioServerSocketChannel}的{@link Class}对象调用{@link ReflectiveChannelFactory#ReflectiveChannelFactory(Class)}创建{@link ReflectiveChannelFactory}实例 赋值给{@link io.netty.bootstrap.AbstractBootstrap#channelFactory}
     *     而{@link ReflectiveChannelFactory}的构造方法就是将{@link ReflectiveChannelFactory#constructor}属性赋值为{@link NioServerSocketChannel}的构造器
     *     </li>
     *     <li>4 {@link ServerBootstrap#bind(int)}->{@link ServerBootstrap#doBind(SocketAddress)}</li>
     *     <ul>
     *         <li>{@link ServerBootstrap#initAndRegister()}中<pre>{@code channel=this.channelFactory.newChannel()}</pre>就是调用已经实例化了的{@link ReflectiveChannelFactory#newChannel()}对象方法 而该方法就是调用<pre>{@code return this.constructor.newInstance()}</pre> 利用反射创建{@link NioServerSocketChannel}的实例</li>
     *     </ul>
     * </ul>
     * 
     * <p><h4>客户端</h4></p>
     */
    public static void main(String[] args) throws Exception {

        // Configure the server.
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); // Netty线程模型 服务端2个group Netty中的线程池
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap(); // 创建服务端实例
            b
                    .group(bossGroup, workerGroup) // 初始化boss和worker线程池
                    .channel(NioServerSocketChannel.class) // Netty中的channel没有使用java原生的ServerSocketChannel和SocketChannel 而是封装了与之对应的NioServerSocketChannel和NioSocketChannel
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .handler(new LoggingHandler(LogLevel.INFO)) // 指定LoggingHandler 这个handler是给服务端收到新的请求的时候处理用的
                    .childHandler(new ChannelInitializer<SocketChannel>() { // childHandler指定的handlers是给新创建的连接用的 服务端ServerSocketChannel在accept一个连接以后需要创建SocketChannel的实例 childHandler中设置的handler就是用于处理新创建的SocketChannel的 而不是用来处理ServerSocketChannel实例的
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception { // pipeline需要ChannelInitializer辅助类 借助辅助类可以指定多个handler组成pipeline 就是拦截器 在每个NioSocketChannel或NioServerSocketChannel实例内部都会有一个pipeline实例 并且还涉及到handler执行顺序
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new EchoServerHandler());
                        }
                    });

            // Start the server.
            ChannelFuture f = b.bind(8007).sync(); // Netty异步编程 main线程调用bind()方法返回一个ChannelFuture bind()方法是一个异步方法 当某个执行线程执行了真正的绑定操作后 那个执行线程会标记这个future为成功 然后main线程调用sync()方法就会返回 如果bind()失败 sync()方法会将异常抛出来 进入finally代码块

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync(); // 绑定端口bind()成功后 进到当前方法 channel()方法获取到该future关联的channel channel.closeFuture()也会返回一个ChannelFuture 然后调用sync()方法 这个sync()方法的返回条件是: 有其他的线程关闭了NioServerSocketChannel 往往是因为需要停掉服务了 然后那个线程会设置future的状态 此时main线程执行sync()方法才会返回
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
