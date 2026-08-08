/*
 * Copyright 2014 The Netty Project
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

import io.netty.util.internal.StringUtil;

import java.lang.reflect.Constructor;

/**
 * A {@link ChannelFactory} that instantiates a new {@link Channel} by invoking its default constructor reflectively.
 */
public class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Constructor<? extends T> constructor;

    /**
     * 根据传进来的channel类把它的构造函数包起来 就是对应的channel的工厂 想什么时候要channel实例就构造一个
     * @param clazz {@link io.netty.channel.socket.nio.NioServerSocketChannel} {@link io.netty.channel.socket.nio.NioSocketChannel}
     */
    public ReflectiveChannelFactory(Class<? extends T> clazz) {
        try {
            // factory持有Channel的无参构造方法 将来创建channel实例就是调用这个构造方法
            this.constructor = clazz.getConstructor(); // NioServerSocket的class对象
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + StringUtil.simpleClassName(clazz) + " does not have a public non-arg constructor", e);
        }
    }

    @Override
    public T newChannel() {
        try {
            /**
             * 把channel的构造方法包成factory交给别人 当它需要channel实例的时候就用factory的这个方法构造一个channel出来
             * 反射调用Channel的无参构造方法创建Channel
             * NioSocketChannel用来读写 它的创建时机在connect的时候
             * NioServerSocketChannel用来连接 它的创建时机在bind的时候
             */
            return this.constructor.newInstance();
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " + constructor.getDeclaringClass(), t);
        }
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ReflectiveChannelFactory.class) + '(' + StringUtil.simpleClassName(constructor.getDeclaringClass()) + ".class)";
    }
}
