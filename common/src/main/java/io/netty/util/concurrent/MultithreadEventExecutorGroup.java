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
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    /**
     * NioEventLoopGroup中维护的NioEventLoop集合 一个group里面有哪些EventLoop
     * NioEventLoop的基类是EventExecutor
     */
    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);

    /**
     * 线程选择器
     * 当出现新的事件需要处理时 线程选择器负责从NioEventLoopGroup的children数组中轮询选取出一个NioEventLoop实例
     *   - IO事件
     *   - 普通事件
     */
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    /**
     *
     * @param nThreads
     *   - server
     *     - bossGroup->1
     *     - workerGroup
     *   - client
     * @param executor->null
     * @param args 3个元素
     *             - SelectorProvider.provider()
     *             - DefaultSelectStrategyFactory.INSTANCE
     *             - RejectedExecutionHandlers.reject()
     */
    protected MultithreadEventExecutorGroup(int nThreads,
                                            Executor executor, // null
                                            Object... args // [SelectorProvider SelectStrategyFactory RejectedExecutionHandlers]
    ) {
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    /**
     * 初始化
     *   - executor任务执行器 将来负责处理任务 提交到NioEventLoop的立即任务\缓存在taskQueue中的任务
     *   - children数组 缓存的是NioEventLoop实例
     *   - chooser 线程选择器 将来有事件达到NioEventLoopGroup后 通过线程选择器委派给某一个具体的NioEventLoop实例 达到负载均衡的效果
     * @param nThreads
     *   - server
     *     - bossGroup->1
     *     - workerGroup
     *   - client
     * @param executor->null
     * @param chooserFactory 线程选择器 从NioEventLoopGroup的children数组中选择一个NioEventLoop实例
     *   - DefaultEventExecutorChooserFactory.INSTANCE
     * @param args 3个元素
     *             - SelectorProvider.provider()
     *             - DefaultSelectStrategyFactory.INSTANCE
     *             - RejectedExecutionHandlers.reject()
     */
    protected MultithreadEventExecutorGroup(int nThreads, // 标识着group中有几个EventLoop
                                            Executor executor, // null
                                            EventExecutorChooserFactory chooserFactory, // DefaultEventExecutorChooserFactory.INSTANCE
                                            Object... args // [SelectorProvider SelectStrategyFactory RejectedExecutionHandlers]
    ) {
        /**
         * 因为将来的任务是存放在NioEventLoop的taskQueue中的
         * Netty的事件模型就是以NioEventLoop组合的线程进行驱动的
         * 所以任务的执行需要依赖任务执行器
         */
        if (executor == null) // 线程执行器 非守护线程(main线程退出可以继续执行)
            executor = new ThreadPerTaskExecutor(this.newDefaultThreadFactory()); // 构造一个executor线程执行器 一个任务对应一个线程(线程:任务=1:n)

        /**
         * 构建NioEventLoop数组
         * NioEventLoop children数组 线程池中的线程数组
         */
        this.children = new EventExecutor[nThreads];

        /**
         * 轮询NioEventLoop数组 让NioEventLoopGroup组件去创建NioEventLoop实例
         * 根据NioEventLoopGroup构造器指定的数量创建NioEventLoop 也就是指定数量的线程数(线程的创建动作延迟到任务提交时)
         */
        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                /**
                 * 初始化NioEventLoop事件循环器集合 也就是多个线程
                 * 让NioEventLoopGroup组件去创建NioEventLoop实例
                 */
                children[i] = this.newChild(executor, args); // args=[SelectorProvider SelectStrategyFactory RejectedExecutionHandlers]
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j ++) { // 但凡有一个child实例化失败 就把已经成功实例化的线程进行shutdown shutdown是异步操作
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt(); // 把中断状态设置回去 交给关心的线程来处理
                            break;
                        }
                    }
                }
            }
        }

        /**
         * 创建线程选择器
         * 线程选择策略
         * NioEventLoopGroup都绑定一个chooser对象 作为线程选择器 通过这个线程选择器
         * 从children数组中给客户端负载均衡出一个NioEventLoop实例
         * 为每一个channel发生的读写IO分配不同的线程进行处理
         */
        this.chooser = chooserFactory.newChooser(children);

        final FutureListener<Object> terminationListener = new FutureListener<Object>() { // 设置一个listener用来监听线程池中的termination事件 给线程池中的每一个线程都设置这个listener 当监听到所有线程都terminate以后 这个线程池就算真正的terminate了
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length)
                    terminationFuture.setSuccess(null);
            }
        };

        for (EventExecutor e: children)
            e.terminationFuture().addListener(terminationListener);

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet); // 只读集合
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        return this.chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    /**
     * 设计模式: 模板方法
     * 让子类去关注方法的实现细节
     * 创建EventExecutor实例->让NioEventLoopGroup组件去实现NioEventLoop的创建
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
