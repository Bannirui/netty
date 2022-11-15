package io.netty.example.basic.future;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 *
 * @since 2022/11/15
 * @author dingrui
 */
public class FutureTest00 {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(3);
        EventLoopGroup group = new DefaultEventLoopGroup(3);

        Future<Long> f = group.submit(() -> {
            System.out.println("task...");
            Thread.sleep(100_000);
            return 100L;
        });

        new Thread(() -> {
            try {
                Long ans = f.get();
                System.out.println("get..." + Thread.currentThread().getName() + " " + ans);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        }, "get").start();

        new Thread(() -> {
            try {
                Long ans = f.sync().getNow();
                System.out.println("sync..." + Thread.currentThread().getName() + " " + ans);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        }, "sync").start();

        new Thread(() -> {
            f.addListener(future -> {
                System.out.println("future..." + Thread.currentThread().getName() + " " + f.get());
                latch.countDown();
            });
        }, "listen").start();

        latch.await();
        group.shutdownGracefully();
    }
}
