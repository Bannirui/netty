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

package io.netty.handler.codec.mqtt;

/**
 * MQTT Message Types.
 * mqtt的fixed_header里面的byte1的高4位值代表的类型
 * 不要把这些类型理解成packet type 它是客户端和broker之间通信使用的控制指令
 */
public enum MqttMessageType {
    /**
     * 客户端连接服务器 我要连接mqtt broker
     * 我是一个mqtt的client
     *   - 我的client id是多少
     *   - 我支持什么版本的mqtt
     *   - keep alive是多少
     *   - 用户名是什么
     *   - 密码是什么
     */
    CONNECT(1),
    /**
     * 服务器确认连接
     * broker收到客户端的connect后会返回connectact告诉客户端是连接成功还是失败
     */
    CONNACK(2),
    // 发布消息 我要发布一条业务消息 broker给其他客户端发送
    PUBLISH(3),
    // 发布确认 你给我发的publish 我已经收到了
    PUBACK(4),
    // 发布收到
    PUBREC(5),
    // 发布释放
    PUBREL(6),
    // 发布完成
    PUBCOMP(7),
    // 订阅主题 我要订阅这些topic
    SUBSCRIBE(8),
    // 订阅确认
    SUBACK(9),
    // 取消订阅
    UNSUBSCRIBE(10),
    // 取消订阅确认
    UNSUBACK(11),
    // pingreq和pingresp是保持mqtt连接活跃 并让broker知道客户端还活着 这也是为什么mqtt特别适合物联网设备
    // 心跳请求
    PINGREQ(12),
    // 心跳响应
    PINGRESP(13),
    /**
     * 断开连接
     * 客户端准备断开连接 告诉broker我要正常断开mqtt会话
     */
    DISCONNECT(14),
    // 认证
    AUTH(15);

    private static final MqttMessageType[] VALUES;

    static {
        // this prevent values to be assigned with the wrong order
        // and ensure valueOf to work fine
        final MqttMessageType[] values = values();
        VALUES = new MqttMessageType[values.length + 1];
        for (MqttMessageType mqttMessageType : values) {
            final int value = mqttMessageType.value;
            if (VALUES[value] != null) {
                throw new AssertionError("value already in use: " + value);
            }
            VALUES[value] = mqttMessageType;
        }
    }

    private final int value;

    MqttMessageType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /**
     * @param type mqtt fixed_header的byte1的高4位
     * @return 对应的类型
     */
    public static MqttMessageType valueOf(int type) {
        if (type <= 0 || type >= VALUES.length) {
            throw new IllegalArgumentException("unknown message type: " + type);
        }
        return VALUES[type];
    }
}

