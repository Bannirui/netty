package io.netty.example.basic.nioeventloop;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 *
 * @since 2022/11/7
 * @author dingrui
 */
public class NioEventLoopTest00 {

    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup();
        group.next().execute(() -> System.out.println("execute..."));
        group.next().submit(() -> System.out.println("submit..."));
    }
}
