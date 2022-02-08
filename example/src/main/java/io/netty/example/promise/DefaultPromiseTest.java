package io.netty.example.promise;

import io.netty.util.concurrent.*;

/**
 * @author dingrui
 * @since 2022/2/8
 * @description 测试实例
 * @see io.netty.util.concurrent.DefaultPromise
 */
public class DefaultPromiseTest {

    public static void main(String[] args) {
        // 构造线程池
        EventExecutor executor = new DefaultEventExecutor();
        // 创建promise实例
        Promise promise = new DefaultPromise(executor);
        // promise添加listener
        promise
                .addListener((GenericFutureListener<Future<Integer>>) future -> {
                    if (future.isSuccess()) System.out.println("任务结束 结果: " + future.get());
                    else System.out.println("任务失败 异常: " + future.cause());
                })
                .addListener((GenericFutureListener<Future<Integer>>) future -> {
                    System.out.println("任务结束");
                });
        // 提交任务到线程池 5s后执行 设置执行promise的结果
        executor.submit(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // 设置promise结果
            promise.setSuccess(121);
        });
        // main线程阻塞等待执行结果
        try {
            promise.sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
