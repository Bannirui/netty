package io.netty.example.basic.eventloopgroup;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.TimeUnit;

/**
 *
 * @since 2022/11/7
 * @author dingrui
 */
public class NioEventLoopGroupTest01 {

    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup();
        group.execute(() -> {
            System.out.println("execute task...");
        });
        group.submit(() -> {
            System.out.println("submit task...");
        });
        group.schedule(() -> {
                    System.out.println("schedule task...");
                },
                5_000,
                TimeUnit.MILLISECONDS);
        System.out.println("main thread end");
    }
}
