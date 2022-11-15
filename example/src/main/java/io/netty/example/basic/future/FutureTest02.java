package io.netty.example.basic.future;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

import java.util.concurrent.ExecutionException;

/**
 *
 * @since 2022/11/15
 * @author dingrui
 */
public class FutureTest02 {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        EventLoopGroup group = new DefaultEventLoopGroup(3);

        Future<Long> f = group.submit(() -> {
            System.out.println("task...");
            Thread.sleep(100_000);
            return 100L;
        });

        new Thread(() -> {
            try {
                Long ans = f.get();
                System.out.println("get1..." + Thread.currentThread().getName() + " " + ans);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }, "get1").start();

        new Thread(() -> {
            try {
                Long ans = f.get();
                System.out.println("get2..." + Thread.currentThread().getName() + " " + ans);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }, "get2").start();
    }
}
