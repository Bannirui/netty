package io.netty.example.basic.buffer;

import java.nio.IntBuffer;

/**
 * @author dingrui
 * @date 2021/9/19
 * @description
 *
 * 当需要与NIO Channel进行交互时 就需要使用到NIO Buffer 数据从Buffer读取到Channel中 并且从Channel中写入到Buffer中
 * 一个Buffer就是一块内存区域 可以在这个内存区域中进行数据的读写 NIO Buffer本质就是对这块内存区域的封装 提供了一些API对数据进行读写
 *
 * Buffer的类型
 *   - ByteBuffer
 *   - CharBuffer
 *   - DoubleBuffer
 *   - FloatBuffer
 *   - IntBuffer
 *   - LongBuffer
 *   - ShortBuffer
 *
 * NIO Buffer的基本使用
 * 使用NIO Buffer的步骤
 *   - 将数据写入到Buffer中
 *   - 调用Buffer.flip()方法将NIO Buffer转换为读模式
 *   - 从Buffer中读取数据
 *   - 调用Buffer.clear()或Buffer.compact()方法将Buffer转换为写模式
 *
 * 将数据写入到Buffer中时，Buffer会记录已经写了多少条数据，当需要从Buffer中读取数据时，必须调用Buffer.flip()将Buffer切换为读模式
 * 一旦读取了所有的Buffer数据，必须清理Buffer，让其可以重新写，清理Buffer可以调用Buffer.clear()或Buffer.compact()
 *
 * Buffer属性
 *   - capacity
 *     - 一块内存会有一个固定的大小 最多会写入capacity个单位的数据到Buffer中
 *   - position
 *     - 当从一个Buffer中写入数据时 我们是从Buffer中的一个确定的位置position开始写入的
 *     - 在最初的状态时 position的值是0 每当写入一个单位的数据后position就会递增1
 *     - 每当从Buffer中读取数据时 也是从某个特定的位置开始读取 当调用flip()方法将Buffer从写模式转换到读模式时 position的值就会被自动设置为0 每当读取一个单位的数据 position的值就递增1
 *     - position表示了读写操作的位置指针
 *     - Buffer.rewind()方法可以重置position的值为0 因此可以重新读取/写入Buffer
 *       - 如果是读模式 重置的是读模式的position
 *       - 如果是写模式 重置的是写模式的position
 *       - rewind()主要是针对读模式 在读模式时 读取到limit后 可以调用rewind()方法 将读取position置为0
 *   - limit
 *     - limit表示此时还可以写入/读取多少单位的数据
 * position和limit的含义与Buffer处于读模式或写模式有关
 * capacity的含义与Buffer所处的模式无关
 *
 * Direct Buffer vs Non-Direct Buffer
 *   - Direct Buffer
 *     - 所分配的内存不在JVM堆上 不受GC管理 但是Direct Buffer的Java对象是由GC管理的 因此当发生GC对象被回收时 Direct Buffer也会被释放
 *     - 因为Direct Buffer不在JVM堆上分配 因此Direct Buffer对应程序的内存占用的影响就不那么明显
 *       - 实际上还是占用了这么多的内存 但是JVM不好统计到非JVM管理的内存
 *     - 申请和释放Direct Buffer的开销比较大 因此正确使用Direct Buffer的方式是在初始化时申请一个Buffer 然后不断复用此Buffer 在程序结束后才释放该Buffer
 *     - 使用Direct Buffer时 当尽心一些底层的系统IO操作时 效率会比较高 因此此时JVM不需要拷贝Buffer中的内存到中间临时缓冲区
 *   - Non-Direct Buffer
 *     - 直接在JVM堆上进行内存的分配 本质上时byte[]数组的封装
 *     - 因为Non-Direct Buffer在JVM堆中 因此当进行系统底层IO操作中时 会将此Buffer中的内存复制到中间临时缓冲区中 因此Non-Direct Buffer的效率比较低
 *
 * mark()和reset()
 *   - 调用Buffer.mark()将当前的position的值保存起来
 *   - 调用Buffer.reset()将position的值恢复回来
 *
 * flip rewind clear
 *   - Buffer的读/写模式共用一个position和limit变量 当从写模式变为读模式时 原先的写position就变成了读模式的limit
 *   - rewind即倒带 仅仅将position置为0
 *   - clear将position设置为0 将limit设置为capacity
 *     - 在一个已经写满数据的buffer中 调用clear可以从头读取buff的数据
 *     - 为了将一个buffer填充满数据 可以调用clear 然后一直写入直到达到limit
 *
 * Buffer的比较
 *   - 两个Buffer是相同类型
 *   - 两个Buffer的剩余的数据个数是相同的
 *   - 两个Buffer的剩余的数据都是相同的
 */
public class BufferTest {

    public static void main(String[] args) {
        IntBuffer intBuffer = IntBuffer.allocate(2);
        intBuffer.put(1);
        intBuffer.put(2);
        intBuffer.flip();
        // 每当调用一次get方法读取数据时 buffer的读指针都会向前移动一个单位长度(一个int长度)
        System.out.println(intBuffer.get());
        System.out.println(intBuffer.get());
    }
}
