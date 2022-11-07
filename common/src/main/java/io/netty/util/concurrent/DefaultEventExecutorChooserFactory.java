/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    /**
     * 策略模式
     * NioEventLoop的线程数是2的倍数 一种线程选择方式
     * NioEventLoop的线程数不是2的倍数 一种线程选择方式
     */
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTwoEventExecutorChooser(executors); // 线程池的线程数量是2的幂次方采用的选择策略
        } else {
            return new GenericEventExecutorChooser(executors); // 线程池的线程数量不是2的幂次方采用的选择策略
        }
    }

    private static boolean isPowerOfTwo(int val) { // 判断是否是2的幂次方
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * next()方法的实现就是选择下一个线程的方法
         * 如果线程数是2的倍数 通过位运算 效率高
         */
        @Override
        public EventExecutor next() { // 线程池线程数是2的幂次方 位运算
            return this.executors[idx.getAndIncrement() & this.executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        // Use a 'long' counter to avoid non-round-robin behaviour at the 32-bit overflow boundary.
        // The 64-bit long solves this by placing the overflow so far into the future, that no system
        // will encounter this in practice.
        private final AtomicLong idx = new AtomicLong();
        private final EventExecutor[] executors; // 在EventLoopGroup构造器中初始化的EventLoop数组

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /*
         * 线程数不是2的倍数 采用绝对值取模的方式 效率一般
         */
        @Override
        public EventExecutor next() { // 线程池线程数量不是2的幂次方 采用取模方式
            return this.executors[(int) Math.abs(idx.getAndIncrement() % this.executors.length)];
        }
    }
}
