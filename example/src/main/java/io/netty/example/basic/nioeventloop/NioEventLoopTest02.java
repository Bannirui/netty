package io.netty.example.basic.nioeventloop;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.IOException;

/**
 *
 * @since 2022/11/7
 * @author dingrui
 */
public class NioEventLoopTest02 {

    public static void main(String[] args) throws InterruptedException, IOException {
        EventLoopGroup group = new NioEventLoopGroup(1);
        group.next().execute(() -> System.out.println(Thread.currentThread() + "::execute..."));
        Thread.sleep(25_000);
        group.next().execute(() -> System.out.println(Thread.currentThread() + "::submit..."));
        Thread.sleep(5_000);
        System.out.println();
    }
}
