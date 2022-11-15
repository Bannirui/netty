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
public class FutureTest01 {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        EventLoopGroup group = new DefaultEventLoopGroup(3);
        Future<Long> f = group.submit(() -> {
            System.out.println("task...");
            Thread.sleep(5_000);
            return 100L;
        });

        Long ans = f.get();
        System.out.println("get..." + Thread.currentThread().getName() + " " + ans);
    }
}