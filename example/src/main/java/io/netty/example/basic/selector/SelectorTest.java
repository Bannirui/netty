package io.netty.example.basic.selector;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * @author dingrui
 * @date 2021/9/22
 * @description
 *
 * Channel注册到选择器中
 *   - 为了使用选择器管理Channel 需要将Channel注册到选择器中
 *   - 如果一个Channel要注册到Selector中 那么这个Channel必须是非阻塞的 即channel.configureBlocking(false)
 *     - 因为Channel必须是要非阻塞的 因此FileChannel不能够使用选择器 因为FileChannel是阻塞的
 *
 * Channel.register()第二个参数指定了对Channel的什么类型事件感兴趣
 *   - Connect 连接事件 TCP连接 对应于SelectionKey.OP_CONNECT
 *   - Accept 确认事件 对应于SelectionKey.OP_ACCEPT
 *   - Read 读事件 对应于SelectionKey.READ 表示buffer可读
 *   - Write 写事件 对应于SelectionKey.WRITE 表示buffer可写
 *
 * 一个Channel发出一个事件也可以称为对于某个事件 Channel准备好了 因此一个Channel成功连接到了另一个服务器也可以称为connect ready
 * 可以使用运算符组合多个事件
 *   - int interestSet = SelectionKey.OP_READ | SelectionKey.OP_WRITE
 *
 * 一个Channel仅仅可以被注册到一个Selector一次 如果将Channel注册到Selector多次 那么其实就是相当于更新SelectionKey的interest set
 *
 * SelectionKey
 * 使用register注册一个Channel时 会返回一个SelectionKey对象 这个对象包括
 *   - interest set
 *     - 感兴趣的事件集
 *     - 可以通过如下方式获取interest set
 *       - int interestSet = selectionKey.interestOps();
 *       - boolean isInterestedInAccept = interestSet & SelectionKey.OP_ACCEPT;
 *       - boolean isInterestedInConnect = interestSet & SelectionKey.OP_CONNECT;
 *   - ready set
 *     - Channel所准备好了的操作
 *     - Ready set的操作
 *       - int readSet = selectionKey.readyOps();
 *       - selectionKey.isAcceptable();
 *       - selectionKey.isConnectable();
 *   - channel
 *   - selector
 *   - attached object
 *     - 可选的附加对象
 *
 * Selector的使用流程
 *   - 通过selector.open()打开一个selector
 *   - 将Channel注册到Selector中 并设置需要监听的事件
 *   - 不断重复
 *     - 调用select()方法
 *     - 调用selector.selectedKeys()获取selected keys
 *     - 迭代每个selected key
 *       - 从selected key中获取对应的Channel和附加信息
 *       - 判断是哪些IO事件已经就绪 然后处理他们
 *       - 根据需要更改selected key的监听事件
 *       - 将已经处理过的key从selected keys集合中移除
 */
public class SelectorTest {

    private static final int BUF_SIZE = 256;
    private static final int TIMEOUT = 3_000;

    public static void main(String[] args) throws Exception {
        // 打开服务端Socket
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        // 打开Selector
        Selector selector = Selector.open();
        // 服务端Socket监听端口 配置非阻塞模式
        serverSocketChannel.socket().bind(new InetSocketAddress(8080));
        serverSocketChannel.configureBlocking(false);
        /**
         * 将channel注册到selector中
         * 通常都是先注册一个OP_ACCEPT事件 然后在OP_ACCEPT到来时 再将这个channel的OP_READ注册到selector中
         */
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while (true) {
            // 阻塞等待channel IO可操作
            if (selector.select(TIMEOUT) == 0) {
                System.out.println(".");
                continue;
            }
            // 获取IO操作就绪的SelectionKey 通过SelectionKey可以知道哪些Channel的哪些IO操作已经就绪
            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                // 当获取到一个SelectionKey后 就要将它删除 表示已经对这个IO事件进行了处理
                keyIterator.remove();
                if (key.isAcceptable()) {
                    /**
                     * 当OP_ACCEPT事件到来时 就从ServerSocketChannel中获取一个SocketChannel代表客户端的连接
                     * 注意:
                     *   - 在OP_ACCEPT事件中 key.channel()返回的Channel是ServerSocketChannel
                     *   - 在OP_READ和OP_WRITE事件中 从key.channel()返回Channel是SocketChannel
                     */
                    SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
                    clientChannel.configureBlocking(false);
                    clientChannel.register(key.selector(), SelectionKey.OP_READ,
                            ByteBuffer.allocate(BUF_SIZE));
                }
                if (key.isReadable()) {
                    SocketChannel clientChannel = ((SocketChannel) key.channel());
                    ByteBuffer buf = ((ByteBuffer) key.attachment());
                    int bytesRead = clientChannel.read(buf);
                    if (bytesRead == -1) {
                        clientChannel.close();
                    } else if (bytesRead > 0) {
                        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        System.out.println("Get data length: " + bytesRead);
                    }
                }
                if (key.isValid() && key.isWritable()) {
                    ByteBuffer buf = ((ByteBuffer) key.attachment());
                    buf.flip();
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    clientChannel.write(buf);
                    if (!buf.hasRemaining()) {
                        key.interestOps(SelectionKey.OP_READ);
                    }
                    buf.compact();
                }
            }
        }
    }
}
