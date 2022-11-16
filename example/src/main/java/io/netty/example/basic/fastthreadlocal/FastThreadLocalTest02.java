package io.netty.example.basic.fastthreadlocal;

import io.netty.util.concurrent.FastThreadLocal;

/**
 *
 * @since 2022/11/16
 * @author dingrui
 */
public class FastThreadLocalTest02 {

    private final static FastThreadLocal<Long> v = new FastThreadLocal<Long>() {
        @Override
        protected Long initialValue() throws Exception {
            System.out.println("init");
            return 0L;
        }
    };

    public static void main(String[] args) throws InterruptedException {
        v.set(1L);
        v.get();
    }
}
