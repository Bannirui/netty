package io.netty.example.basic.eventloopgroup;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.TimeUnit;

/**
 * @author dingrui
 * @since 2023/5/6
 */
public class NioEventLoopGroupTest02 {

    public static void main(String[] args) {
//        test00();
        test01();
    }

    private static void test00() {
        NioEventLoopGroup eg = new NioEventLoopGroup();
        /**
         * 任务-普通任务
         */
        eg.execute(() -> System.out.println("execute::任务-普通任务"));
        // 任务-普通任务
        eg.submit(() -> System.out.println("submit::任务-普通任务"));
        // 任务-定时任务
        eg.schedule(() -> System.out.println("schedule::任务-定时任务"), 10_000L, TimeUnit.MILLISECONDS);
        // 任务-定时任务
        eg.scheduleAtFixedRate(() -> System.out.println("scheduleAtFixedRate::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);
        // 任务-定时任务
        eg.scheduleWithFixedDelay(() -> System.out.println("scheduleWithFixedDelay::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);
        // IO(网络IO)事件
        eg.register(new NioSocketChannel());
    }

    private static void test01() {
        NioEventLoopGroup eg = new NioEventLoopGroup();
        EventLoop el = eg.next();
        /**
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        el.execute(() -> System.out.println("execute::任务-普通任务"));
        /**
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        el.submit(() -> System.out.println("submit::任务-普通任务"));
        /**
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.schedule(() -> System.out.println("schedule::任务-定时任务"), 10_000L, TimeUnit.MILLISECONDS);
        /**
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.scheduleAtFixedRate(() -> System.out.println("scheduleAtFixedRate::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);
        /**
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.scheduleWithFixedDelay(() -> System.out.println("scheduleWithFixedDelay::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);
        /**
         * IO(网络IO)事件
         * SingleThreadEventLoop
         */
        el.register(new NioSocketChannel());
    }
}
