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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@link EventExecutorGroup} is responsible for providing the {@link EventExecutor}'s to use
 * via its {@link #next()} method. Besides this, it is also responsible for handling their
 * life-cycle and allows shutting them down in a global fashion.
 *
 */

/**
 * 从类图的全局关系上很明显的看出这个类是Netty和Java体系的分水岭
 *   - 首先 命名上体现出Event 对于事件我的理解是
 *     - 广义上 一个程序的所有输入都可以看作为事件 程序本体固化为事件驱动系统
 *     - 狭义上 在为Netty的NioEventLoop具体实现做原型设计 事件就是Socket的IO读写
 *   - 软件设计风格和特性区别 在EventExecutorGroup之上全是Java体系的设计 在此之后都是Netty体系的设计
 *   - 为什么EventExecutorGroup是顶层 即为啥它是EventExecutor的基类
 *     - 姑且将EventExecutor称为事件执行器 EventExecutorGroup称为事件执行器管理器
 *     - 管理器的语义 它由N个执行器组成
 *       - 管理器不仅对外负责事件任务
 *       - 对内还要负责执行器管理编排
 *     - 作者的设计是事件执行器是一种特殊的事件执行器管理器
 *       - EventExecutor继承自EventExecutorGroup
 *       - EventExecutorGroup是多个线程的执行器
 *       - EventExecutor是1个线程的执行器
 *       - EventExecutor接口的实现要关注的next()方法只能返回自身实例
 *     - 如果是我来设计 很可能就是EventExecutorGroup和EventExecutor不存在继承关系 在派生类中通过组合方式定义二者关系
 *   - EventExecutorGroup还继承了Iterable 这个区别于Java对Executor设计的
 *     - 我对Executor的理解就是在解耦任务提交-执行基础之上 完全闭合Executor的实现 也就是不对外开放内部细节
 *     - EventExecutorGroup这是对客户端开放了内部的EventExecutor
 *       - 这样设计肯定是有场景必须要访问到内部的工作线程 也就是EventExecutor
 *       - Netty要将Selector跟EventExecutor线程绑定 再将Channel注册到Selector上
 *  - 扩展了ExecutorService中的任务执行器关闭定义
 *    - 增加了多种API 更加细粒度地控制了关闭行为 这点由使用场景决定
 *    - 关闭的API的返回值支持了Future 这点是Netty的特点 提供了异步编程的支持 将来客户端可以异步监听关闭而不用傻傻地同步等待
 */
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {

    /**
     * Returns {@code true} if and only if all {@link EventExecutor}s managed by this {@link EventExecutorGroup}
     * are being {@linkplain #shutdownGracefully() shut down gracefully} or was {@linkplain #isShutdown() shut down}.
     */
    boolean isShuttingDown();

    /**
     * Shortcut method for {@link #shutdownGracefully(long, long, TimeUnit)} with sensible default values.
     *
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully();

    /**
     * Signals this executor that the caller wants the executor to be shut down.  Once this method is called,
     * {@link #isShuttingDown()} starts to return {@code true}, and the executor prepares to shut itself down.
     * Unlike {@link #shutdown()}, graceful shutdown ensures that no tasks are submitted for <i>'the quiet period'</i>
     * (usually a couple seconds) before it shuts itself down.  If a task is submitted during the quiet period,
     * it is guaranteed to be accepted and the quiet period will start over.
     *
     * @param quietPeriod the quiet period as described in the documentation
     * @param timeout     the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
     *                    regardless if a task was submitted during the quiet period
     * @param unit        the unit of {@code quietPeriod} and {@code timeout}
     *
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    /**
     * Returns the {@link Future} which is notified when all {@link EventExecutor}s managed by this
     * {@link EventExecutorGroup} have been terminated.
     */
    Future<?> terminationFuture();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    List<Runnable> shutdownNow();

    /**
     * Returns one of the {@link EventExecutor}s managed by this {@link EventExecutorGroup}.
     */
    EventExecutor next();

    @Override
    Iterator<EventExecutor> iterator();

    @Override
    Future<?> submit(Runnable task);

    @Override
    <T> Future<T> submit(Runnable task, T result);

    @Override
    <T> Future<T> submit(Callable<T> task);

    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
