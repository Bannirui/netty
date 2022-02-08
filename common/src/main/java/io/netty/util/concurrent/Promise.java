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

/**
 * Special {@link Future} which is writable.
 */
public interface Promise<V> extends Future<V> { // Promise实例内部是一个任务 任务的执行往往是异步的 通常是一个线程池来处理任务 Promise提供的setSuccess()或setFailure()将来会被某个执行任务的线程在执行完成后回调 同时那个线程在调用setSuccess()或setFailure()后会回调listeners的回调函数(回调的具体内容不一定非要执行任务的线程自己执行 也可以创建新的线程执行或者将回调任务提交到某个线程池来执行) 而且一旦setSuccess()或setFailure()后那些await()或sync()的线程就会从等待中返回 => 两种编程方式 一种是await() 等await()方法返回后得到promise的执行结果然后处理它 一种是提供listener实例 不太关心任务什么时候执行完 只要执行完以后会去执行listener中的处理方法就行

    /**
     * Marks this future as a success and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setSuccess(V result); // 标记该future成功及设置其执行结果 并且会通知所有的listeners 如果该操作失败 将抛出异常(失败指的是该future已经有了结果 成功的结果或者失败的结果)

    /**
     * Marks this future as a success and notifies all
     * listeners.
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a success. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     */
    boolean trySuccess(V result); // 和setSuccess()一样 只不过如果失败了 它不会抛出异常 返回false

    /**
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setFailure(Throwable cause); // 标记该future失败及其失败的原因 如果失败 将抛出异常(失败指的是已经有了结果)

    /**
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a failure. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     */
    boolean tryFailure(Throwable cause); // 和setFailure()一样 标记future失败 如果已经有了结果 不会抛出异常 返回false

    /**
     * Make this future impossible to cancel.
     *
     * @return {@code true} if and only if successfully marked this future as uncancellable or it is already done
     *         without being cancelled.  {@code false} if this future has been cancelled already.
     */
    boolean setUncancellable(); // 标记future不可以被取消

    @Override
    Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> await() throws InterruptedException;

    @Override
    Promise<V> awaitUninterruptibly();

    @Override
    Promise<V> sync() throws InterruptedException;

    @Override
    Promise<V> syncUninterruptibly();
}
