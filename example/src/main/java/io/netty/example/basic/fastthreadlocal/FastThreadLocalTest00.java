package io.netty.example.basic.fastthreadlocal;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

/**
 *
 * @since 2022/11/16
 * @author dingrui
 */
public class FastThreadLocalTest00 {

    private final static FastThreadLocal<Long> v = new FastThreadLocal<Long>() {
        @Override
        protected Long initialValue() throws Exception {
            System.out.println("init");
            return 0L;
        }
    };

    public static void main(String[] args) throws InterruptedException {
        new FastThreadLocalThread(() -> {
            System.out.println("fast1 v1=" + v.get());
            v.set(1L);
            System.out.println("fast1 v2=" + v.get());
            v.remove();
            System.out.println("fast1 v3=" + v.get());
        }).start();

        new FastThreadLocalThread(() -> {
            System.out.println("fast2 v1=" + v.get());
            v.set(2L);
            System.out.println("fast2 v2=" + v.get());
            v.remove();
            System.out.println("fast2 v3=" + v.get());
        }).start();

        Thread.sleep(3_000);
    }
}
