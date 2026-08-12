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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

/**
 * See <a href="https://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html#fixed-header">
 *     MQTTV3.1/fixed-header</a>
 *
 * Bit         7   6   5   4               |     3   |  2   1 |   0
 * byte 1   MQTT Control Packet type       |   DUP   |  QoS   | RETAIN
 * byte 2…                         Remaining Length
 *
 * byte2...是变长编码 最少用1个字节 最多用4个字节
 * 所以fixed header最少2个字节 最多5个字节
 *
 * byte2...每个字节的高7位是标识是不是变长 要不要继续解析 剩下的低[6...0]这7位才是真正的有效值
 * 因为这两个原因 1是只有4个字节的上限 2是每个字节做多只能用7位 mqtt为了这么点bit能表达更大的length
 * 就采用了128进制
 *   第1个字节表达的长度=第1个字节的低7位有效值*128^0*
 *   第2个字节表达的长度=第2个字节的低7位有效值*128^1
 *   第3个字节表达的长度=第2个字节的低7位有效值*128^2
 *   第4个字节表达的长度=第2个字节的低7位有效值*128^3
 *
 * byte1的高4位是mqtt的类型对应的值
 * byte1的低4位按照位有不同的作用
 *      Bits    3  |  2    1  |  0
 *             DUP |   QoS    | RETAIN
 */
public final class MqttFixedHeader {

    private final MqttMessageType messageType;
    private final boolean isDup;
    private final MqttQoS qosLevel;
    private final boolean isRetain;
    // remain length是包含了fixed header后面的的variable header+payload
    private final int remainingLength;

    /**
     *
     * @param messageType fixed_header的byte1高4位值对应的类型
     * @param isDup fixed_header的byte1低3位的标识DUP
     * @param qosLevel fixed_header的byte1的低[2...1]的值
     * @param isRetain fixed_header的byte1的低0位表示RETAIN
     * @param remainingLength fixed_header的byte2...解析出来的remain length
     */
    public MqttFixedHeader(
            MqttMessageType messageType,
            boolean isDup,
            MqttQoS qosLevel,
            boolean isRetain,
            int remainingLength) {
        this.messageType = ObjectUtil.checkNotNull(messageType, "messageType");
        this.isDup = isDup;
        this.qosLevel = ObjectUtil.checkNotNull(qosLevel, "qosLevel");
        this.isRetain = isRetain;
        this.remainingLength = remainingLength;
    }

    public MqttMessageType messageType() {
        return messageType;
    }

    public boolean isDup() {
        return isDup;
    }

    public MqttQoS qosLevel() {
        return qosLevel;
    }

    public boolean isRetain() {
        return isRetain;
    }

    public int remainingLength() {
        return remainingLength;
    }

    @Override
    public String toString() {
        return new StringBuilder(StringUtil.simpleClassName(this))
            .append('[')
            .append("messageType=").append(messageType)
            .append(", isDup=").append(isDup)
            .append(", qosLevel=").append(qosLevel)
            .append(", isRetain=").append(isRetain)
            .append(", remainingLength=").append(remainingLength)
            .append(']')
            .toString();
    }
}
