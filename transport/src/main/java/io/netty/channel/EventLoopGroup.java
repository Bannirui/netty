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
package io.netty.channel;

import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Special {@link EventExecutorGroup} which allows registering {@link Channel}s that get
 * processed for later selection during the event loop.
 *
 */

/**
 * 开始跟IO扯上关系
 * 拓展的功能是Channel的注册
 *   - 什么是注册 注册给谁 所谓的注册就是将Channel注册到多路复用器上
 *   - 多路复用器跟线程又是绑定的 所以可以范性理解成将IO任务跟执行线程绑定
 *   - 为什么register()的原型是定义在EventLoopGroup而不是EventLoop
 *     - 此处EventLoopGroup与EventLoop的继承关系恰如之前的EventExecutorGroup与EventExecutor的关系
 *       - EventExecutor继承自EventExecutorGroup
 *       - EventLoop继承自EventLoopGroup
 *     - 实际的注册发生所在是EventLoop 但是要经由EventLoopGroup
 *       - 也就是为啥不是这样
 *         - EventLoop继承自EventLoopGroup
 *         - EventLoopGroup空接口 只起到标识作用
 *         - register()方法原型声明在EventLoop接口中
 *       - 首先还是EventLoopGroup有管理器的角色作用
 *       - EventLoop不会也不应该单独作用 更多的场景都是依赖于EventLoopGroup进行下达指令
 *       - 为实现Reactor模型做铺垫 将方法声明在EventLoopGroup中 未来才能在1个EventLoopGroup实例中向1...N个其他的EventLoopGroup实例注册 也就是mainReactor向subReactor派发任务
 */
public interface EventLoopGroup extends EventExecutorGroup {
    /**
     * Return the next {@link EventLoop} to use
     */
    @Override
    EventLoop next();

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The returned {@link ChannelFuture}
     * will get notified once the registration was complete.
     */
    ChannelFuture register(Channel channel);

    /**
     * Register a {@link Channel} with this {@link EventLoop} using a {@link ChannelFuture}. The passed
     * {@link ChannelFuture} will get notified once the registration was complete and also will get returned.
     */
    ChannelFuture register(ChannelPromise promise);

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The passed {@link ChannelFuture}
     * will get notified once the registration was complete and also will get returned.
     *
     * @deprecated Use {@link #register(ChannelPromise)} instead.
     */
    @Deprecated
    ChannelFuture register(Channel channel, ChannelPromise promise);
}
