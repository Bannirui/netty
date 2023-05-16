/*
 * Copyright 2013 The Netty Project
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

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public final class ThreadPerTaskExecutor implements Executor { // 只有一个execute方法 每来一个任务就新建一个线程 这个线程池不是给NioEventLoopGroup使用的 而是给NioEventLoop使用的

    /**
     * 负责创建线程
     */
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    /**
     * 任务执行器
     * 一般用来在线程池\任务执行器的实现中负责驱动任务的执行
     * @param command 提交给任务执行的具体任务
     */
    @Override
    public void execute(Runnable command) {
        /**
         * 资源懒加载
         * 在Java中线程是宝贵的资源
         * Java线程:OS线程=1:1
         * 针对这么宝贵的线程 可以立即进行Thread构造方法的属性赋值 但是不要继续调用start()方法
         *   - start()放触发系统调用 用户空间和内核空间切换 开销较大
         *   - 就等到用的时候再进行系统调用 使线程状态处于就绪
         *   - 等待CPU的调度 被CPU调度起来之后会回调进入entry point 内核->Thread::run->command::run(用户指定的代码片段)
         */
        threadFactory.newThread(command).start();
    }
}
