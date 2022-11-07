package io.netty.example.basic.nioeventloop;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 *
 * @since 2022/11/7
 * @author dingrui
 */
public class NioEventLoopTest01 {

    public static void main(String[] args) throws Exception {
        System.out.println();
        Selector sl = Selector.open();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress(8080));
        ssc.configureBlocking(false);
        ssc.register(sl, SelectionKey.OP_ACCEPT);
        sl.wakeup();
        int cnt = sl.select();
        System.out.println(cnt);
    }
}
