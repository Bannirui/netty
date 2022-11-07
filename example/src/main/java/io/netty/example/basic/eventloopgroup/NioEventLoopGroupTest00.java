package io.netty.example.basic.eventloopgroup;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 *
 * @since 2022/11/7
 * @author dingrui
 */
public class NioEventLoopGroupTest00 {

    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup(3);
        for (int i = 0; i < 4; i++) {
            EventLoop el = group.next();
            System.out.println(el);
        }
    }
}
