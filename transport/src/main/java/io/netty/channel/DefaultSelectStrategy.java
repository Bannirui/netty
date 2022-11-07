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
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    /**
     * selectSupplier回调接口
     *     - 在NioEventLoop中是IO多路复用器Selector的非阻塞方式执行select()方法 返回值只有两种情况
     *         - 0值 没有Channel处于IO事件就绪状态
     *         - 正数 IO事件就绪的Channel数量
     * hasTasks
     *     - taskQueue常规任务队列或者tailTasks收尾任务队列不为空就界定为有待执行任务 hasTasks为True
     *
     * 也就是说如果任务队列有任务待执行 使用非阻塞方式执行一次复用器的select()操作
     * 如果任务队列都是空的 就直接准备以阻塞方式执行一次复用器的select()操作
     */
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
