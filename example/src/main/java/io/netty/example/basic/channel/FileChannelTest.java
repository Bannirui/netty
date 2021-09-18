package io.netty.example.basic.channel;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * @author dingrui
 * @date 2021/9/18
 * @description
 *
 * NIO的IO操作都是从Channel开始的 一个channel类似于一个stream
 *   - 可以在同一个Channel中执行读和写操作 然而同一个Stream仅仅支持读或写
 *   - Channel可以同步非阻塞读写 Stream只能同步阻塞读写
 *   - Channel总是从Buffer中读取数据 或写入数据到Buffer中
 *
 * Channel的类型 通道涵盖了UDP和TCP网络IO以及文件IO
 *   - FileChannel 文件操作
 *   - DatagramChannel UDP操作
 *   - SocketChannel TCP操作
 *   - ServerSocketChannel TCP操作 使用在服务器端
 *
 * FileChannel是文件操作的Channel 可以通过FileChannel从一个文件中读取数据 也可以将数据写入到文件中
 *   - FileChannel不能设置为非阻塞模式
 */
public class FileChannelTest {

    private static final String FILE_PATH = "/Users/dingrui/Code/Git/java/netty/example/src/main/java/io/netty/example/basic/channel/channel-test.txt";

    public static void main(String[] args) throws Exception {
        // 读数据
        FileChannelTest.read();
        // 写数据
        FileChannelTest.write();
    }

    private static void read() throws Exception {
        // 打开FileChannel
        RandomAccessFile rAFile =
                new RandomAccessFile(
                        FileChannelTest.FILE_PATH,
                        "rw");
        FileChannel inChannel = rAFile.getChannel();
        // 从FileChannel中读取数据
        ByteBuffer buf = ByteBuffer.allocate(48);
        int bytesRead = inChannel.read(buf);
        while (bytesRead != -1) {
            buf.flip();
            while (buf.hasRemaining()) {
                System.out.println(((char) buf.get()));
            }
            buf.clear();
            bytesRead = inChannel.read(buf);
        }
        rAFile.close();
    }

    private static void write() throws Exception {
        // 打开FileChannel
        RandomAccessFile rAFile =
                new RandomAccessFile(
                        FileChannelTest.FILE_PATH,
                        "rw");
        FileChannel inChannel = rAFile.getChannel();
        String newData = "new string to write to file..." + System.currentTimeMillis();
        ByteBuffer buf = ByteBuffer.allocate(48);
        buf.clear();
        buf.put(newData.getBytes(StandardCharsets.UTF_8));
        buf.flip();
        while (buf.hasRemaining()) {
            inChannel.write(buf);
        }
        inChannel.close();
    }
}
