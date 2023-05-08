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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 */

/**
 * EventExecutor继承自EventExecutorGroup
 * EventExecutor是一种特殊的EventExecutorGroup
 *   - EventExecutorGroup是多个线程的执行器
 *   - EventExecutor是1个线程的执行器
 * 因此 在EventExecutorGroup基础上做定制化扩展
 *   - 约束next()方法的返回为自身实例
 *   - 为什么要inEventLoop() 我理解为2件事情做铺垫
 *     - 作为执行器EventExecutor是不独立工作的 它将来一定是工作于EventExecutorGroup之中 而EventExecutorGroup对外开放了任务\事件的提交
 *       - 任务提交线程只关注提交
 *       - EventExecutor就是工作线程 它关注执行
 *       - 因此提供inEventLoop()判定 用来转移代码的执行权 实现异步编程
 *     - 在上面的基础之上 当所有事情都是通过异步方式提交到了工作线程上 那么站在工作线程视角上 这些任务就天然有序 可以保证一些资源或者前置动作的成功
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     * EventExecutor是一种特殊的EventExecutorGroup
     * 单个线程执行器的EventExecutorGroup
     * 因此EventExecutor的派生实现中next()方法返回实例自身
     */
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     */
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     */
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     */
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
