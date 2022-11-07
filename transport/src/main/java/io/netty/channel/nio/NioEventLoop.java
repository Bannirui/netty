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
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop { // netty线程池中的单个线程指的就是NioEventLoop实例

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION = SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector; // Netty优化过的Java IO多路复用器
    private Selector unwrappedSelector; // Java原生的IO多路复用器
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider; // IO多路复用器提供器 用于创建多路复用器实现

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE); // 如果NioEventLoop线程处于阻塞状态 下一次啥时候将它唤醒

    private final SelectStrategy selectStrategy; // 这个select是针对taskQueue任务队列中任务的选择策略

    private volatile int ioRatio = 50; // IO任务的执行事件比例 每个线程既有IO任务执行 又有非IO任务执行 该参数为了保证有足够的时间给IO
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, // 标识EventLoop归属于哪个group
                 Executor executor, // 线程执行器 将线程和EventLoop绑定
                 SelectorProvider selectorProvider, // Java中IO多路复用器提供器
                 SelectStrategy strategy, // 正常任务队列选择策略
                 RejectedExecutionHandler rejectedExecutionHandler, // 正常任务队列拒绝策略
                 EventLoopTaskQueueFactory taskQueueFactory, // 正常任务
                 EventLoopTaskQueueFactory tailTaskQueueFactory // 收尾任务
    ) {
        super(parent,
                executor,
                false,
                newTaskQueue(taskQueueFactory), // 正常任务队列
                newTaskQueue(tailTaskQueueFactory), // 收尾任务队列
                rejectedExecutionHandler
        ); // 调用父类构造方法
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider"); // IO多路复用器提供器 用于创建多路复用器实现
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy"); // 这个select是针对taskQueue任务队列中任务的选择策略
        final SelectorTuple selectorTuple = this.openSelector(); // 开启NIO中的组件 selector 意味着NioEventLoopGroup这个线程池中每个线程NioEventLoop都有自己的selector
        /**
         * 创建NioEventLoop绑定的selector对象
         * 初始化了IO多路复用器
         */
        this.selector = selectorTuple.selector; // Netty优化过的IO多路复用器
        this.unwrappedSelector = selectorTuple.unwrappedSelector; // Java原生的多路复用器
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector; // Java原生的IO多路复用器
        final Selector selector; // Netty优化了Java原生的IO多路复用器

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector; // 从命名就可以看出来Netty对Java的多路复用器做了封装
        try {
            /**
             * jdk底层的api
             * 创建了Java的IO多路复用器selector
             */
            unwrappedSelector = this.provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        /**
         * 判断是否需要关闭优化
         * 默认false 也就说默认需要进行优化
         * netty要对jdk原生的selector进行优化 selector在select()操作的时候 会通过selector.selectedKeys()操作返回一个Set<SelectionKey> 这个是Set类型 netty对这个set进行了处理 使用SelectedSelectionKeySet这个数据结构进行了替换 当在select()操作时将key存入一个SelectedSelectionKeySet数据结构中
         */
        if (DISABLE_KEY_SET_OPTIMIZATION) return new SelectorTuple(unwrappedSelector); // 不需要优化 直接使用Java原生的复用器实现

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    /**
                     * 反射获取sun.nio.ch.SelectorImpl这个类的class对象
                     */
                    return Class.forName("sun.nio.ch.SelectorImpl", false, PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        /**
         * 判断拿到的class对象是不是Class对象是不是Selector的实现类
         */
        if (!(maybeSelectorImplClass instanceof Class) || !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass()))
            return new SelectorTuple(unwrappedSelector);

        // 这个class对象是Selector的实现
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        /**
         * 自定义数据结构替代jdk原生的SelectionKeySet
         */
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    /**
                     * 通过反射拿到
                     * selectedKeys属性
                     * publicSelectedKeys属性
                     * 这两个属性都是HashSet的实现方式
                     */
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset = PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    /**
                     * 将拿到的两个属性设置成可修改的
                     */
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) return cause;
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) return cause;

                    /**
                     * 将selector的两个属性都换成netty的selectedKeySet实现的数据结构
                     */
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            return new SelectorTuple(unwrappedSelector);
        }
        /**
         * 将优化后的keySet保存成NioEventLoop的成员变量
         */
        selectedKeys = selectedKeySet;
        return new SelectorTuple(unwrappedSelector, new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * netty解决epoll bug的步骤就是创建一个新的selector 将旧selector中注册的channel和事件重新注册到新的selector中 然后将自身selector属性替换成新创建的selector
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) return;

        try {
            /**
             * 重新创建一个select
             */
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        /**
         * 拿到旧select中所有的key
         */
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null)
                    continue;
                /**
                 * 获取key注册的事件
                 */
                int interestOps = key.interestOps();
                /**
                 * 将key注册的事件取消
                 */
                key.cancel();
                /**
                 * 注册到重新创建的selector中
                 */
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                /**
                 * 如果channel是NioChannel 就重新赋值
                 */
                if (a instanceof AbstractNioChannel) ((AbstractNioChannel) a).selectionKey = newKey;
                nChannels ++;
            } catch (Exception e) {
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        int selectCnt = 0; // 当前EventLoop事件循环器代表的线程执行复用器select空轮询操作计数
        for (;;) {
            try {
                int strategy;
                try {
                    /**
                     * strategy这个值只有3种情况 决定复用器如何执行(阻塞/非阻塞)
                     *     - 任务队列为空->-1->复用器即将以阻塞方式执行一次
                     *     - 任务队列(常规任务队列taskQueue+收尾任务队列tailTasks)有任务 复用器以非阻塞方式执行一次
                     *         - 没有IO事件->0
                     *         - 有IO事件->Channel数量
                     *
                     * 这样设计的方式是不要让阻塞调用复用器导致既有任务不能即使执行
                     */
                    strategy = this.selectStrategy.calculateStrategy(this.selectNowSupplier, super.hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE: // -2
                        continue;

                    case SelectStrategy.BUSY_WAIT: // -3
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT: // -1 任务队列为空 将线程阻塞在复用器上 唤醒时机有两种情况(阻塞期间有IO事件到达 阻塞指定事件后主动结束阻塞开始执行定时任务)
                        long curDeadlineNanos = super.nextScheduledTaskDeadlineNanos(); // 定时任务队列中下一个待执行定时任务还有多久可以被唤醒执行 -1表示没有定时任务可以执行
                        if (curDeadlineNanos == -1L) curDeadlineNanos = NONE; // nothing on the calendar // 边界情况 没有定时任务要执行
                        this.nextWakeupNanos.set(curDeadlineNanos); // 下一次啥时候将线程唤醒
                        try {
                            if (!super.hasTasks()) strategy = this.select(curDeadlineNanos); // select()方法阻塞 超时时间是为了执行可能存在的定时任务 如果没有定时任务就将一直阻塞在复用器的select()操作上等待被唤醒
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                selectCnt++; // 前面任务队列有任务 执行了一次复用器是空轮询
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio; // 默认值是50
                boolean ranTasks; // 标识taskQueue中任务都被执行过一轮
                if (ioRatio == 100) { // 100->先执行IO操作 然后在finally代码块中执行taskQueue中的任务
                    try {
                        /**
                         * 处理轮询到的key
                         */
                        if (strategy > 0) this.processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        ranTasks = super.runAllTasks();
                    }
                } else if (strategy > 0) { // 不是100 根据IO操作耗时 限制非IO操作耗时
                    final long ioStartTime = System.nanoTime();
                    try {
                        /**
                         * 执行IO操作
                         * 处理轮询到的key
                         */
                        this.processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 计算耗时 IO操作耗时
                        final long ioTime = System.nanoTime() - ioStartTime;
                        /**
                         * 执行task
                         * ioRatio的默认值是50 所以runAllTasks()方法最终的入参就是ioTime
                         */
                        ranTasks = super.runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else
                    ranTasks = super.runAllTasks(0); // This will run the minimum number of tasks

                if (ranTasks || strategy > 0) selectCnt = 0;
                else if (unexpectedSelectorWakeup(selectCnt)) selectCnt = 0;
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) return;
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        /**
         * selectedKeys是经过netty优化过的数据结构替代了jdk原生的方式 如果经过select()操作监听到了事件 selectedKeys的数组就会有值
         */
        if (selectedKeys != null)
            this.processSelectedKeysOptimized();
        else
            this.processSelectedKeysPlain(selector.selectedKeys());
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        /**
         * for循环遍历数组
         */
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 获取当前的selectionKey
            final SelectionKey k = selectedKeys.keys[i];
            /**
             * 数组当前引用设置为null 因为selector不会自动清空
             * 与使用原生selector时候 通过遍历selector.selectedKeys()的set的时候 拿到key之后要执行remove()是一样的
             */
            selectedKeys.keys[i] = null;
            // 获取channel NioServerSocketChannel
            final Object a = k.attachment();
            // 根据channel类型调用不同的处理方法
            if (a instanceof AbstractNioChannel)
                processSelectedKey(k, (AbstractNioChannel) a);
            else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        /**
         * 获取channel中的unsafe
         */
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        /**
         * 如果key是不合法的 说明这个channel可能有问题
         */
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }
        /**
         * 执行到这 说明当前的key是合法的
         */
        try {
            /**
             * 拿到key中的io事件
             */
            int readyOps = k.readyOps();
            // 连接事件
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }
            // 写事件
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }
            /**
             * 读事件和连接事件
             * 如果当前NioEventLoop是worker线程 这里就是op_read事件
             * 如果当前NioEventLoop是boss线程 这里就是op_accept事件
             *
             * 无论处理op_read事件还是op_accept事件 都走的unsafe的read()方法 这里unsafe是通过channel获取到的
             * 如果处理的是accept事件 这里的channel是NioServerSocketChannel 与之绑定的是{@link io.netty.channel.nio.AbstractNioMessageChannel.NioMessageUnsafe#unsafe}
             * 如果处理的是op_read事件 处理的线程是worker线程 这里的channel是{@link io.netty.channel.socket.nio.NioServerSocketChannel} 与之绑定的unsafe对象是{@link io.netty.channel.nio.AbstractNioByteChannel.NioByteUnsafe} 会进入{@link AbstractNioByteChannel.NioByteUnsafe#read()}方法
             */
            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0)
                unsafe.read();
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) { // 唤醒阻塞在复用器上的线程 NioEventLoop跟线程绑定了 自己阻塞在了复用器上后只能通过其他线程唤醒自己
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) { // 唤醒条件(NioEventLoop外部线程 NioEventLoop线程阻塞在复用器上[不是AWAKE已经被唤醒状态])
            this.selector.wakeup(); // 唤醒阻塞在复用器上的线程
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow(); // IO多路复用器以非阻塞方式执行select()方法
    }

    private int select(long deadlineNanos) throws IOException { // 阻塞方式执行一次复用器select()操作
        if (deadlineNanos == NONE) return selector.select(); // 永久阻塞
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
