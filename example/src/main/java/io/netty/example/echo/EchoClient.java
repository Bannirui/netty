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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Sends one message when a connection is open and echoes back any received
 * data to the server.  Simply put, the echo client initiates the ping-pong
 * traffic between the echo client and server by sending the first message to
 * the server.
 */
public final class EchoClient {

    public static void main(String[] args) throws Exception {
        // Configure the client.
        EventLoopGroup workerGroup = new NioEventLoopGroup(); // 客户端1个group Netty中的多个线程
        try {
            Bootstrap b = new Bootstrap(); // 创建客户端实例
            b
                .group(workerGroup)
             .channel(NioSocketChannel.class) // 根据NioSocketChannel创建了ChannelFactory->在下面connect()时机->ChannelFactory创建NioSocketChannel实例创建
             .option(ChannelOption.TCP_NODELAY, true)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(new EchoClientHandler());
                 }
             }); // 指定handler 客户端处理请求过程中使用的handlers

            // Start the client.
            ChannelFuture f = b.connect("127.0.0.1", 8007).sync(); // Netty异步编程 main线程调用connect()方法 connect()方法是个异步方法 当某个线程执行了真正的connect操作后 那个线程会调用setSuccess()方法设置future成功了 如果connect失败 那个线程会setFailure()设置future为失败 如果成功了 main线程就可以通过sync()方法拿到返回 如果失败了main线程会在sync()方法抛出异常进到finally代码块

            // Wait until the connection is closed.
            f.channel().closeFuture().sync(); // 客户端connect成功之后开到这行代码 channel()方法获取该future关联的channel channel.closeFuture()也是一个异步方法 然后main线程调用sync()拿到返回或者抛出异常 sync()拿到返回的条件是: 有某个线程关闭了SocketChannel 往往是因为需要停掉服务 然后那个线程通过setSuccess()方法设置future为成功或者通过setFailure()方法设置future为失败
        } finally {
            // Shut down the event loop to terminate all threads.
            workerGroup.shutdownGracefully();
        }
    }
}
