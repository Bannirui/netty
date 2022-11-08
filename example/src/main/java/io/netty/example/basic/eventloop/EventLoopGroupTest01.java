package io.netty.example.basic.eventloop;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 *
 * @since 2022/11/8
 * @author dingrui
 */
public class EventLoopGroupTest01 {

    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup bizGroup = new DefaultEventLoopGroup();
        new ServerBootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast("handler1", new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        // TODO: 2022/11/8 业务处理1
                                        ctx.fireChannelRead(msg);
                                    }
                                })
                                .addLast(bizGroup, "handler1", new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        // TODO: 2022/11/8 业务处理2
                                    }
                                });
                    }
                })
                .bind(8080)
                .sync();
    }
}
