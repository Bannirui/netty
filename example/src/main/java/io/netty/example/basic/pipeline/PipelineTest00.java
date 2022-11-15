package io.netty.example.basic.pipeline;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 *
 * @since 2022/11/15
 * @author dingrui
 */
public class PipelineTest00 {

    public static void main(String[] args) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline()
                .addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        System.out.println("1");
                        ctx.fireChannelRead(msg);
                    }
                })
                .addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void read(ChannelHandlerContext ctx) throws Exception {
                        System.out.println("2");
                        ctx.read();
                    }
                })
                .addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                        System.out.println("3");
                        ctx.fireChannelRegistered();
                    }
                })
                .addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void read(ChannelHandlerContext ctx) throws Exception {
                        System.out.println("4");
                        ctx.read();
                    }
                })
                .addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        System.out.println("5");
                        ctx.fireChannelRead(msg);
                    }
                });
        System.out.println("入站");
        ch.pipeline().fireChannelRead("");
        System.out.println();
        System.out.println("出站");
        ch.pipeline().read();
    }
}