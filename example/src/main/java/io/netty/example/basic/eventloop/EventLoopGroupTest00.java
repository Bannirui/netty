package io.netty.example.basic.eventloop;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;

/**
 *
 * @since 2022/11/8
 * @author dingrui
 */
public class EventLoopGroupTest00 {

    public static void main(String[] args) {
        EventLoopGroup group = new DefaultEventLoopGroup();
        group.next().execute(()-> System.out.println("execute..."));
        System.out.println();
    }
}
