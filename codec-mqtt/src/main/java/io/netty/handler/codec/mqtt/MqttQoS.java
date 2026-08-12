/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.mqtt;

/**
 * fixed_header的byte1低4位上的第2位和第3位组合表示QoS字段
 * 两个bit组合的有效值就是0 1 2 3
 * 对应的枚举
 */
public enum MqttQoS {
    // publish完事 最多就1次 不需要确认
    AT_MOST_ONCE(0),
    // publish->puback 至少1次 发送方没有收到puback可能会重新发送 因此可能出现重新消息
    AT_LEAST_ONCE(1),
    // publish->pubrec->pubrel->pubcomp 恰好1次 这4个packet type就是一组完整的QoS2状态机
    EXACTLY_ONCE(2),
    FAILURE(0x80);

    private final int value;

    MqttQoS(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static MqttQoS valueOf(int value) {
        switch (value) {
        case 0:
            return AT_MOST_ONCE;
        case 1:
            return AT_LEAST_ONCE;
        case 2:
            return EXACTLY_ONCE;
        case 0x80:
            return FAILURE;
        default:
            throw new IllegalArgumentException("invalid QoS: " + value);
        }
    }
}
