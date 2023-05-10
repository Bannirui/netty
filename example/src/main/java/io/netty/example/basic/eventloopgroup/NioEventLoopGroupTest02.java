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
        test00();
        // test01();
    }

    /**
     * 分析NioEventLoopGroup组件对任务的提交\管理\调度
     */
    private static void test00() {
        NioEventLoopGroup eg = new NioEventLoopGroup();

        /**
         * 这个方法的声明->Executor
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        eg.execute(() -> System.out.println("execute::任务-普通任务"));

        /**
         * 这个方法的声明->ExecutorService
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        eg.submit(() -> System.out.println("submit::任务-普通任务"));

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务-一次性
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        eg.schedule(() -> System.out.println("schedule::任务-定时任务-一次性"), 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务-周期性
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        eg.scheduleAtFixedRate(() -> System.out.println("scheduleAtFixedRate::任务-定时任务-周期性"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务-周期性
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        eg.scheduleWithFixedDelay(() -> System.out.println("scheduleWithFixedDelay::任务-定时任务-周期性"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->EventLoopGroup
         * IO(网络IO)事件
         * SingleThreadEventLoop
         */
        eg.register(new NioSocketChannel());
    }

    private static void test01() {
        NioEventLoopGroup eg = new NioEventLoopGroup();
        EventLoop el = eg.next();

        /**
         * 这个方法的声明->Executor
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        el.execute(() -> System.out.println("execute::任务-普通任务"));

        /**
         * 这个方法的声明->ExecutorService
         * 任务-普通任务
         * SingleThreadEventExecutor::taskQueue
         */
        el.submit(() -> System.out.println("submit::任务-普通任务"));

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.schedule(() -> System.out.println("schedule::任务-定时任务"), 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.scheduleAtFixedRate(() -> System.out.println("scheduleAtFixedRate::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->ScheduledExecutorService
         * 任务-定时任务
         * AbstractScheduledEventExecutor::scheduledTaskQueue
         */
        el.scheduleWithFixedDelay(() -> System.out.println("scheduleWithFixedDelay::任务-定时任务"), 10_000L, 10_000L, TimeUnit.MILLISECONDS);

        /**
         * 这个方法的声明->EventLoopGroup
         * IO(网络IO)事件
         * SingleThreadEventLoop
         */
        el.register(new NioSocketChannel());
    }
}
