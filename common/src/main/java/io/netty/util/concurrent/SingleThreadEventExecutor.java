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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    private static final int ST_NOT_STARTED = 1;
    private static final int ST_STARTED = 2;
    private static final int ST_SHUTTING_DOWN = 3;
    private static final int ST_SHUTDOWN = 4;
    private static final int ST_TERMINATED = 5;

    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    /**
     * 非IO任务队列(普通任务和定时任务)
     * 1 通过EventLoopGroup或者EventLoop提供的API提交的任务(execute/submit/schedule...)
     * 2 从定时任务队列中找到的符合运行时机的定时任务
     * 3 WAKEUP_TASK
     *
     * 实现上在NioEventLoop和DefaultEventLoop上差异
     *     - NioEventLoop 通过复用器阻塞/唤起实现线程的阻塞/唤起 不需要队列具有阻塞机制(阻塞队列反而会降低存取效率)
     *     - DefaultEventLoop 通过阻塞队列机制实现线程的阻塞/唤起
     */
    private final Queue<Runnable> taskQueue;

    /**
     * NioEventLoop的父类一定有一个会持有Thread
     * 这样才能保证线程的运行模型可控
     * 在Java中线程:OS线程=1:1
     * 因此线程资源的实例化是后置到任务被执行的时候
     */
    private volatile Thread thread; // 线程执行器持有一个线程 每个Executor持有一个线程(相当于有且只有一个线程的线程池)
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;

    /**
     * 强化原始的Executor任务执行器
     * 将Executor跟NioEventLoop绑定起来
     * Executor本身是ThreadPerTaskExecutor实例 创建线程这个动作延迟到任务执行的时候
     */
    private final Executor executor;
    private volatile boolean interrupted;

    private final CountDownLatch threadLock = new CountDownLatch(1);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();

    /**
     * 标识位
     * 如何唤醒阻塞线程
     * 因为NioEventLoop的实现中 线程阻塞发生点是在复用器的select()上 而不是发生在任务队列的空条件上 所以唤醒方式也不是根据任务队列情况 而是借助复用器 因此这个属性设置为false
     * 换言之
     *     - 如果实现类中 阻塞发生在队列上 线程唤醒就要借助队列 addTaskWakesUp设置为true
     *     - 如果实现类中 阻塞不是发生队列上(比如NioEventLoop中是发生在复用器操作上) 则addTaskWakesUp设置为false
     */
    private final boolean addTaskWakesUp;
    private final int maxPendingTasks;

    /**
     * RejectedExecutionHandlers.reject()
     */
    private final RejectedExecutionHandler rejectedExecutionHandler;

    private long lastExecutionTime; // 最近一次执行任务的时间

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    private volatile long gracefulShutdownQuietPeriod;
    private volatile long gracefulShutdownTimeout;
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, // EventLoop线程归属的管理器
                                        Executor executor, // 线程执行器
                                        boolean addTaskWakesUp, // EventLoop是单线程 不能让一个线程没有任务时候处于空转状态 以事件响应机制来驱动线程执行 所以需要一定机制让那个线程阻塞/唤起 在NioEventLoop中利用IO多路复用器机制实现 在DefaultEventLoop中使用阻塞队列机制实现 addTaskWakesUp为true表示使用阻塞队列实现
                                        int maxPendingTasks,
                                        RejectedExecutionHandler
                                                rejectedHandler
    ) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp; // NioEventLoop和DefaultEventLoop差异
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = this.newTaskQueue(this.maxPendingTasks); // NioEventLoop和DefaultEventLoop差异
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * 属性赋值
     *   - addTaskWakesUp 默认值false
     *   - maxPendingTasks
     *   - executor Executor跟NioEventLoop绑定之后形成的新的Executor
     *   - taskQueue MPSC任务队列
     *   - rejectedExecutionHandler RejectedExecutionHandlers.reject()的返回值
     * @param parent NioEventLoop归属的NioEventLoopGroup
     * @param executor ThreadPerTaskExecutor的实例
     * @param addTaskWakesUp 默认值false
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent,
                                        Executor executor,
                                        boolean addTaskWakesUp,
                                        Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler
    ) { // 所以本质上每个线程也是一个线程池(单线程线程池)
        super(parent); // 设置parent 也就是NioEventLoopGroup实例
        this.addTaskWakesUp = addTaskWakesUp; // 标识唤醒阻塞线程的方式 NioEventLoop阻塞发生在复用器操作上 因此这个设置为false
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        /**
         * 强化原始的Executor任务执行器
         * 将Executor跟NioEventLoop绑定起来
         * Executor本身是ThreadPerTaskExecutor实例 创建线程这个动作延迟到任务执行的时候
         */
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue"); // 创建任务队列 提交给NioEventLoop的任务都会进入到这个taskQueue中等待被执行 这个taskQueue容量默认值16 任务队列 NioEventLoop需要负责IO事件和非IO事件 通常它都是在执行selector::select方法或者正在处理selectedKeys 如果要submit一个任务给它 任务就会被放到taskQueue中 等它来轮询 该队列是线程安全的LinkedBlockingQueue
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler"); // 任务队列taskQueue的默认容量是16 如果submit的任务堆积到了16 再往里面提交任务就会触发拒绝策略的执行
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) { // DefaultEventLoop不支持复用器 阻塞点发生在任务队列的存取上 因此任务队列的实现使用阻塞队列 NioEventLoop阻塞点发生在复用器上 因此不需要依赖阻塞队列 自己单独去实现
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) {
            interrupted = true;
        } else {
            currentThread.interrupt();
        }
    }

    /**
     * @see Queue#poll()
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) { // 从非IO任务队列中取任务
        for (;;) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                return task;
            }
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = super.peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take(); // 阻塞点 阻塞队列为空了发生线程阻塞
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS); // 阻塞点
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll(); // 非阻塞方式
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 从定时任务队列中找到可执行的定时任务
     * 从定时任务中找到可以执行的任务将任务添加到非IO任务队列taskQueue中
     */
    private boolean fetchFromScheduledTaskQueue() {
        if (this.scheduledTaskQueue == null || this.scheduledTaskQueue.isEmpty()) return true;
        // 从定时任务队列中寻找截止时间为nanoTime的任务
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        for (;;) {
            Runnable scheduledTask = super.pollScheduledTask(nanoTime); // 可执行的定时任务
            if (scheduledTask == null) return true;
            // 添加到普通任务队列过程失败就重新添加回定时任务队列中
            if (!this.taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                this.scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask); // taskQueue常规任务队列已经满了 再把定时任务放回远处 等待下一轮执行时机
                return false;
            }
        }
    }

    /**
     * @return {@code true} if at least one scheduled task was executed.
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * @see Queue#peek()
     */
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (!this.offerTask(task)) {
            reject(task);
        }
    }

    final boolean offerTask(Runnable task) {
        if (isShutdown()) {
            reject();
        }
        return this.taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    protected boolean removeTask(Runnable task) {
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert super.inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false;

        do {
            fetchedAll = this.fetchFromScheduledTaskQueue(); // 尝试从定时任务队列中找到所有可执行的定时任务放到非IO任务队列taskQueue中
            if (this.runAllTasksFrom(this.taskQueue)) ranAtLeastOne = true;
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        if (ranAtLeastOne) this.lastExecutionTime = ScheduledFutureTask.nanoTime(); // 尝试更新线程最近一次执行任务的时间
        this.afterRunningAllTasks(); // 收尾任务
        return ranAtLeastOne;
    }

    /**
     * Execute all expired scheduled tasks and all current tasks in the executor queue until both queues are empty,
     * or {@code maxDrainAttempts} has been exceeded.
     * @param maxDrainAttempts The maximum amount of times this method attempts to drain from queues. This is to prevent
     *                         continuous task execution and scheduling from preventing the EventExecutor thread to
     *                         make progress and return to the selector mechanism to process inbound I/O events.
     * @return {@code true} if at least one task was run.
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = this.pollTaskFrom(taskQueue); // 非IO任务
        if (task == null) {
            return false;
        }
        for (;;) {
            super.safeExecute(task);
            task = this.pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * What ever tasks are present in {@code taskQueue} when this method is invoked will be {@link Runnable#run()}.
     * @param taskQueue the task queue to drain.
     * @return {@code true} if at least {@link Runnable#run()} was called.
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        safeExecute(task);
        // Use taskQueue.poll() directly rather than pollTaskFrom() since the latter may
        // silently consume more than one item from the queue (skips over WAKEUP_TASK instances)
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) { // 带超时控制 每执行一批任务(64个)才统计一次时间 因为统计时间这个操作本身会触发系统调用 比较耗时 所以不能为了辅助性工作影响主线任务
        // 定时任务队列中聚合任务 也就是从定时任务中找到可以执行的任务添加到普通任务队列taskQueue中
        fetchFromScheduledTaskQueue();
        // 从普通taskQ中拿一个任务
        Runnable task = pollTask();
        if (task == null) {
            // task为空 跑完所有的任务执行首位的操作 直接返回
            afterRunningAllTasks();
            return false;
        }
        // 执行到这说明任务队列不为空 先计算一个任务执行截止时间
        final long deadline = timeoutNanos > 0 ? ScheduledFutureTask.nanoTime() + timeoutNanos : 0;
        long runTasks = 0;
        long lastExecutionTime;
        // 执行每一个任务
        for (;;) {
            safeExecute(task);
            // 标识符 标记当前跑完的任务数
            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            if ((runTasks & 0x3F) == 0) { // 当跑完64个任务会计算一下当前时间
                lastExecutionTime = ScheduledFutureTask.nanoTime(); // 定时任务初始化到当前时间
                if (lastExecutionTime >= deadline) break; // 如果超过了截止时间就不再执行
            }
            // 执行到这说明还没超过截止时间 继续从普通任务队列中取任务 直到任务取完队列为空
            task = this.pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime(); // 记录下最后执行时间
                break;
            }
        }
        // 收尾工作
        this.afterRunningAllTasks();
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * Run the tasks in the {@link #taskQueue}
     */
    /**
     * 为什么这个地方声明了一个run()的抽象方法
     * 可以这么判断
     * 谁持有Thread 跟Thread扯上关系 谁就关注Thread被回调之后要执行的entry point的方法
     * 因此
     * 当前类持有Thread
     * 所以它要声明一个方法让子类去关注 以便控制Thread的线程模型
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) { // NioEventLoop覆写了这个方法 有自己的特定实现
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            this.taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
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
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        threadLock.await(timeout, unit);

        return isTerminated();
    }

    @Override
    public void execute(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        // !(task instanceof LazyRunnable) && wakesUpForTask(task) -> true
        // this.execute(task, true);
        this.execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    @Override
    public void lazyExecute(Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    private void execute(Runnable task, boolean immediate) {
        /**
         * NioEventLoop只有一个线程 且它的阻塞点只有在IO多路复用器操作上
         * 因此当前添加任务的线程
         *     - NioEventLoop线程自己给自己添加任务 说明它压根没有被阻塞 而且肯定已经处于运行中状态
         *         - 这个线程已经被创建执行 那么这个新添加的任务被放到了非IO任务队列中 迟早会被取出来执行
         *     - 不是NioEventLoop线程 是其他线程往NioEventLoop添加任务
         *         - 如果NioEventLoop线程还没被创建执行 那么相当于任务裹挟着线程进行延迟创建并执行任务
         *         - 非IO任务队列没有任务 也没有IO事件到达时 NioEventLoop线程迟早会阻塞在复用器上
         *             - 阻塞期间有IO事件到达 退出select阻塞继续工作
         *             - 有定时任务还可能超时退出select NioEventLoop线程继续工作
         *             - 没有定时任务就永远阻塞 唤醒的方式 只有外部线程往NioEventLoop添加新任务触发selector复用器的wakeup()
         */
        boolean inEventLoop = super.inEventLoop();
        this.addTask(task); // 添加任务到taskQueue中 如果任务队列已经满了 就触发拒绝策略(抛异常)
        if (!inEventLoop) {
            this.startThread(); // NioEventLoop线程创建启动的时机就是提交进来的第一个异步任务
            if (this.isShutdown()) {
                boolean reject = false;
                try {
                    if (removeTask(task)) reject = true;
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) reject();
            }
        }

        // NioEventLoop中线程阻塞点有且只有一个是在复用器上 因此addTaskWakesUp为false
        if (!addTaskWakesUp && immediate) this.wakeup(inEventLoop); // 唤醒阻塞的线程 这个wakeup(...)方法在子类NioEventLoop中特定的实现(借助复用器进行唤醒阻塞在复用器上的线程)
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly();
                thread = this.thread;
                assert thread != null;
            }

            threadProperties = new DefaultThreadProperties(thread);
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * @deprecated use {@link AbstractEventExecutor.LazyRunnable}
     */
    @Deprecated
    protected interface NonWakeupRunnable extends LazyRunnable { }

    /**
     * Can be overridden to control which tasks require waking the {@link EventExecutor} thread
     * if it is waiting so that they can be run immediately.
     */
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() { // NioEventLoop线程被创建启动的时机(原子变量+CAS方式确保NioEventLoop线程只能被启动一次)
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) { // state状态标识线程已经启动 CAS确保线程只能被创建启动一次
                boolean success = false;
                try {
                    this.doStartThread(); // 启动线程 在每个NioEventLoop只会被执行一次 保证NioEventLoop:线程=1:1
                    success = true;
                } finally {
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED); // 线程启动失败依然将state标识为未启动
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    private void doStartThread() {
        assert thread == null;
        this.executor.execute(new Runnable() { // 这个executor就是实例化NioEventLoop时候传进来的ThreadPerTaskExecutor实例 每次来一个任务创建一个线程 调用execute()方法就会创建一个新的线程 也就是真正的线程Thread实例 Netty中Reactor模型=IO复用器select+多EventLoop线程 所以实际上这个executor线程执行器执行时机有且只有一次 保证一个EventLoop永远只跟一个线程实例班绑定
            @Override
            public void run() {
                /**
                 * 将Executor线程执行器跟线程绑定 线程跟NioEventLoop已经绑定 相当于是NioEvent->Thread->Executor 至于为什么多加一层的映射 是为了利用Executor抽象出来的更好用的API
                 * 记录线程也会为了后面判断执行线程是不是就是NioEventLoop线程自己 通过线程切换方式 保证既定任务整个生命周期都是同一个NioEventLoop线程在执行
                 */
                thread = Thread.currentThread();
                if (interrupted) thread.interrupt();

                boolean success = false;
                updateLastExecutionTime();
                try {
                    SingleThreadEventExecutor.this.run(); // 执行run()方法 该方法为抽象方法 在NioEventLoop中有实现 线程的死循环(不断地处理IO任务和非IO任务)
                    success = true;
                } catch (Throwable t) {
                } finally {
                    for (;;) {
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN))
                            break;
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks. At this point the event loop
                        // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                        // graceful shutdown with quietPeriod.
                        for (;;) if (confirmShutdown()) break;

                        // Now we want to make sure no more tasks can be added from this point. This is
                        // achieved by switching the state. Any new tasks beyond this point will be rejected.
                        for (;;) {
                            int oldState = state;
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN))
                                break;
                        }

                        // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                        // No need to loop here, this is the final pass.
                        confirmShutdown();
                    } finally {
                        try {
                            cleanup();
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.countDown();
                            int numUserTasks = drainTasks();
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
