/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;

import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link MultithreadEventLoopGroup} implementations which is used for NIO {@link Selector} based {@link Channel}s.
 */
public class NioEventLoopGroup extends MultithreadEventLoopGroup { // 事件循环器管理器负责管理事件循环器(创建 使用 销毁) 会实例化所有的NioEventLoop实例(指定线程数量或者cpu*2) 但是并没有真正创建NioEventLoop中真实的线程Thread实例 Thread实例的创建时机是在第一个任务提交过来的时候 channel的register操作就是第一个任务

    /**
     * Create a new instance using the default number of threads, the default {@link ThreadFactory} and
     * the {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup() {
        this(0);
    }

    /**
     * Create a new instance using the specified number of threads, {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads) {
        this(nThreads, (Executor) null);
    }

    /**
     * Create a new instance using the default number of threads, the given {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(ThreadFactory threadFactory) {
        this(0, threadFactory, SelectorProvider.provider());
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory) {
        this(nThreads, threadFactory, SelectorProvider.provider());
    }

    public NioEventLoopGroup(int nThreads, Executor executor) {
        /**
         * executor用于开启NioEventLoop线程所需要的线程执行器
         * SelectorProvider.provider()用于创建selector
         */
        this(nThreads, executor, SelectorProvider.provider());
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the given
     * {@link SelectorProvider}.
     */
    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory, final SelectorProvider selectorProvider) {
        this(nThreads, threadFactory, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory,
        final SelectorProvider selectorProvider, final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, threadFactory, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, final SelectorProvider selectorProvider) {
        this(nThreads, executor, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads,
                             Executor executor, // null
                             final SelectorProvider selectorProvider, // 创建Java的NIO复用器
                             final SelectStrategyFactory selectStrategyFactory
    ) {
        super(nThreads, executor, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(
            int nThreads,
            Executor executor,
            EventExecutorChooserFactory chooserFactory,
            final SelectorProvider selectorProvider,
            final SelectStrategyFactory selectStrategyFactory,
            final RejectedExecutionHandler rejectedExecutionHandler
    ) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler);
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory,
                             final RejectedExecutionHandler rejectedExecutionHandler,
                             final EventLoopTaskQueueFactory taskQueueFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler, taskQueueFactory);
    }

    /**
     * @param nThreads the number of threads that will be used by this instance.
     * @param executor the Executor to use, or {@code null} if default one should be used.
     * @param chooserFactory the {@link EventExecutorChooserFactory} to use.
     * @param selectorProvider the {@link SelectorProvider} to use.
     * @param selectStrategyFactory the {@link SelectStrategyFactory} to use.
     * @param rejectedExecutionHandler the {@link RejectedExecutionHandler} to use.
     * @param taskQueueFactory the {@link EventLoopTaskQueueFactory} to use for
     *                         {@link SingleThreadEventLoop#execute(Runnable)},
     *                         or {@code null} if default one should be used.
     * @param tailTaskQueueFactory the {@link EventLoopTaskQueueFactory} to use for
     *                             {@link SingleThreadEventLoop#executeAfterEventLoopIteration(Runnable)},
     *                             or {@code null} if default one should be used.
     */
    public NioEventLoopGroup(int nThreads, // 线程池中的线程数 就是NioEventLoop的实例数量
                             Executor executor, // 本身就要构造一个线程池Executor 现在又传进来一个executor实例 这个实例不是给线程池使用的 而是给NioEventLoop使用的
                             EventExecutorChooserFactory chooserFactory, // 当提交一个任务到线程池的时候 线程池需要选择其中的一个线程执行这个任务 chooserFactory就是实现选择策略的
                             SelectorProvider selectorProvider, // SelectorProvider.provider() 通过selectorProvider实例化jdk的Selector 每个线程池都持有一个selectorProvider实例
                             SelectStrategyFactory selectStrategyFactory, // DefaultSelectStrategyFactory.INSTANCE 涉及到线程在做select操作和执行任务过程中的策略选择问题
                             RejectedExecutionHandler rejectedExecutionHandler, // RejectedExecutionHandlers.reject() Netty选择的默认拒绝策略是抛出异常 线程池中没有可用线程时执行任务的情况时使用 这个是给NioEventLoop使用的
                             EventLoopTaskQueueFactory taskQueueFactory,
                             EventLoopTaskQueueFactory tailTaskQueueFactory
    ) { // 参数最全的构造方法
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler, taskQueueFactory, tailTaskQueueFactory); // 调用父类构造方法
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the child event loops.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).setIoRatio(ioRatio);
        }
    }

    /**
     * Replaces the current {@link Selector}s of the child event loops with newly created {@link Selector}s to work
     * around the  infamous epoll 100% CPU bug.
     */
    public void rebuildSelectors() {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).rebuildSelector();
        }
    }

    @Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception { // executor=ThreadPerTaskExecutor实例 args=[SelectorProvider SelectStrategyFactory RejectedExecutionHandlers]
        SelectorProvider selectorProvider = (SelectorProvider) args[0]; // Java中对IO多路复用器的实现 依赖Jdk的版本 Window=WindowsSelectorProvider MacOSX=KQueueSelectorProvider Linux=EPollSelectorProvider
        SelectStrategyFactory selectStrategyFactory = (SelectStrategyFactory) args[1]; // DefaultSelectStrategyFactory实例 任务选择策略(如何从taskQueue任务队列中选择一个任务)
        RejectedExecutionHandler rejectedExecutionHandler = (RejectedExecutionHandler) args[2]; // RejectedExecutionHandlers实例
        EventLoopTaskQueueFactory taskQueueFactory = null;
        EventLoopTaskQueueFactory tailTaskQueueFactory = null;

        int argsLength = args.length;
        if (argsLength > 3) taskQueueFactory = (EventLoopTaskQueueFactory) args[3]; // null
        if (argsLength > 4) tailTaskQueueFactory = (EventLoopTaskQueueFactory) args[4]; // null
        return new NioEventLoop(this, // this是NioEventLoopGroup实例 在构造NioEventLoop的时候将线程是实例传给parent属性
                executor, // ThreadPerTaskExecutor实例
                selectorProvider,
                selectStrategyFactory.newSelectStrategy(), // taskQueue任务队列中有任务就poll一个任务出来执行 空的就阻塞等待任务到来
                rejectedExecutionHandler, // taskQueue任务队列满了拒绝策略(向上抛异常)
                taskQueueFactory, // 非IO任务队列
                tailTaskQueueFactory // 收尾任务队列
        ); // NioEventLoop就是NioEventLoopGroup这个线程池中的个体 相当于线程池中的线程 在每个NioEventLoop实例内部都持有一个自己Thread实例
    }
}
