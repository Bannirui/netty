package io.netty.example.basic.nioeventloop;

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 *
 * @since 2022/11/8
 * @author dingrui
 */
public class AccessControllerTest00 {

    public static void main(String[] args) {
        Object ans = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                System.out.println("test...");
                return null;
            }
        });
        System.out.println();
    }
}
