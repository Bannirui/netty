package io.netty.example.basic;

import io.netty.util.NettyRuntime;

/**
 * @author dingrui
 * @since 2023/5/16
 */
public class UtilTest {

    public static void main(String[] args) {
        /**
         * 6核12线程
         * 12逻辑线程
         */
        int cnt = NettyRuntime.availableProcessors();
        System.out.println();
    }
}
