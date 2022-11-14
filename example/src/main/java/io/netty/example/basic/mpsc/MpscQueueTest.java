package io.netty.example.basic.mpsc;

import io.netty.util.internal.PlatformDependent;
import org.jctools.queues.MpscChunkedArrayQueue;
import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.Queue;

/**
 *
 * @since 2022/11/14
 * @author dingrui
 */
public class MpscQueueTest {

    public static void main(String[] args) {
        Queue<Integer> q0 = PlatformDependent.<Integer>newMpscQueue(10);
        Queue<Integer> q1 = new MpscChunkedArrayQueue<>(1024, 2048);
        Integer v = q1.poll();
        Queue<Integer> q2 = new MpscUnboundedArrayQueue<>(10);
        q2.offer(3);
        System.out.println();
    }
}
