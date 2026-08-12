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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.mqtt.MqttDecoder.DecoderState;
import io.netty.handler.codec.mqtt.MqttProperties.IntegerProperty;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.ObjectUtil;

import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidClientId;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidMessageId;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidPublishTopicName;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.resetUnusedFields;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.validateFixedHeader;
import static io.netty.handler.codec.mqtt.MqttConstant.DEFAULT_MAX_BYTES_IN_MESSAGE;
import static io.netty.handler.codec.mqtt.MqttConstant.DEFAULT_MAX_CLIENT_ID_LENGTH;
import static io.netty.handler.codec.mqtt.MqttSubscriptionOption.RetainedHandlingPolicy;

/**
 * Decodes Mqtt messages from bytes, following
 * the MQTT protocol specification
 * <a href="https://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html">v3.1</a>
 * or
 * <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html">v5.0</a>, depending on the
 * version specified in the CONNECT message that first goes through the channel.
 */
public final class MqttDecoder extends ReplayingDecoder<DecoderState> {

    /**
     * States of the decoder.
     * We start at READ_FIXED_HEADER, followed by
     * READ_VARIABLE_HEADER and finally READ_PAYLOAD.
     */
    /**
     * mqtt协议格式是 fixed header+variable header+payload
     * 数据是源源不断流的方式从tcp过来 不能说解析一般发现数据不够一个协议就丢掉 所以用状态机的方式
     * 意味着下次准备解析数据是从什么样状态开始的
     * 初始的时候肯定从fixed_header开始的
     */
    enum DecoderState {
        READ_FIXED_HEADER,
        READ_VARIABLE_HEADER,
        READ_PAYLOAD,
        BAD_MESSAGE,
    }

    private MqttFixedHeader mqttFixedHeader;
    private Object variableHeader;
    // 先解析fixed header里面的remain length=variable header+payload 再根据fixed header里面的类型分别解析出来variable header 剩下payload占多少字节就知道了
    private int bytesRemainingInVariablePart;

    private final int maxBytesInMessage;
    private final int maxClientIdLength;

    public MqttDecoder() {
      this(DEFAULT_MAX_BYTES_IN_MESSAGE, DEFAULT_MAX_CLIENT_ID_LENGTH);
    }

    public MqttDecoder(int maxBytesInMessage) {
        this(maxBytesInMessage, DEFAULT_MAX_CLIENT_ID_LENGTH);
    }

    public MqttDecoder(int maxBytesInMessage, int maxClientIdLength) {
        // 初始化的时候肯定标记协议从fixed_header开始解析
        super(DecoderState.READ_FIXED_HEADER);
        this.maxBytesInMessage = ObjectUtil.checkPositive(maxBytesInMessage, "maxBytesInMessage");
        this.maxClientIdLength = ObjectUtil.checkPositive(maxClientIdLength, "maxClientIdLength");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        switch (state()) {
            case READ_FIXED_HEADER: try {
                // 开始解析fixed_header
                mqttFixedHeader = decodeFixedHeader(ctx, buffer);
                bytesRemainingInVariablePart = mqttFixedHeader.remainingLength();
                // fixed_header已经解析出来了 保存现在已经读到什么地方了 推进状态机 准备读variable header
                checkpoint(DecoderState.READ_VARIABLE_HEADER);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_VARIABLE_HEADER:  try {
                // 开始解析variable header
                final Result<?> decodedVariableHeader = decodeVariableHeader(ctx, buffer, mqttFixedHeader);
                variableHeader = decodedVariableHeader.value;
                if (bytesRemainingInVariablePart > maxBytesInMessage) {
                    buffer.skipBytes(actualReadableBytes());
                    throw new TooLongFrameException("too large message: " + bytesRemainingInVariablePart + " bytes");
                }
                // fixed header里面解析到的remain length是包含了fixed header后面的variable header和payload 现在variable header解析出来了知道了在tcp字节流里面variable header占了多少字节
                // 那么剩下来的payload占多少字节
                bytesRemainingInVariablePart -= decodedVariableHeader.numberOfBytesConsumed;
                // variable header已经解析出来了 保存现在已经读到什么地方了 推进状态机 准备读payload
                checkpoint(DecoderState.READ_PAYLOAD);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_PAYLOAD: try {
                // 开始解析payload
                final Result<?> decodedPayload =
                        decodePayload(
                                ctx,
                                buffer,
                                mqttFixedHeader.messageType(),
                                bytesRemainingInVariablePart,
                                maxClientIdLength,
                                variableHeader);
                bytesRemainingInVariablePart -= decodedPayload.numberOfBytesConsumed;
                if (bytesRemainingInVariablePart != 0) {
                    throw new DecoderException(
                            "non-zero remaining payload bytes: " +
                                    bytesRemainingInVariablePart + " (" + mqttFixedHeader.messageType() + ')');
                }
                // payload已经解析出来了 保存现在已经读到什么地方了 推进状态机 现在已经解析了一个完整的mqtt协议了 那么下一次要解析的是下一个协议的fixed header
                checkpoint(DecoderState.READ_FIXED_HEADER);
                MqttMessage message = MqttMessageFactory.newMessage(
                        mqttFixedHeader, variableHeader, decodedPayload.value);
                mqttFixedHeader = null;
                variableHeader = null;
                // 一个完整的mqtt协议完整的解析出来了 才放到out里面让netty传给pipeline里面后面的handler
                out.add(message);
                break;
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case BAD_MESSAGE:
                // Keep discarding until disconnection.
                buffer.skipBytes(actualReadableBytes());
                break;

            default:
                // Shouldn't reach here.
                throw new Error();
        }
    }

    private MqttMessage invalidMessage(Throwable cause) {
      checkpoint(DecoderState.BAD_MESSAGE);
      return MqttMessageFactory.newInvalidMessage(mqttFixedHeader, variableHeader, cause);
    }

    /**
     * Decodes the fixed header. It's one byte for the flags and then variable
     * bytes for the remaining length.
     *
     * @see
     * https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/errata01/os/mqtt-v3.1.1-errata01-os-complete.html#_Toc442180841
     *
     * @param buffer the buffer to decode from
     * @return the fixed header
     */
    /**
     * 从tcp字节流里面解析出mqtt的fixed_header
     * Bit         7   6   5   4               |     3   |  2   1 |   0
     * byte 1   MQTT Control Packet type       |   DUP   |  QoS   | RETAIN
     * byte 2…                         Remaining Length
     *
     * byte2...是变长编码 最少用1个字节 最多用4个字节
     * 每个字节的高7位是标识是不是变长 要不要继续解析 剩下的低[6...0]这7位才是真正的有效值
     * 因为这两个原因 1是只有4个字节的上限 2是每个字节做多只能用7位 mqtt为了这么点bit能表达更大的length
     * 就采用了128进制
     * 第1个字节表达的长度=第1个字节的低7位有效值*128^0
     * 第2个字节表达的长度=第2个字节的低7位有效值*128^1
     * 第3个字节表达的长度=第2个字节的低7位有效值*128^2
     * 第4个字节表达的长度=第2个字节的低7位有效值*128^3
     *
     * byte1的高4位是mqtt的类型对应的值
     * byte1的低4位按照位有不同的作用
     *      Bits    3  |  2    1  |  0
     *             DUP |   QoS    | RETAIN
     * @param ctx
     * @param buffer 里面是tcp字节流的数据
     * @return fixed_header
     */
    private static MqttFixedHeader decodeFixedHeader(ChannelHandlerContext ctx, ByteBuf buffer) {
        // fixed_header的byte1
        short b1 = buffer.readUnsignedByte();
        // mqtt fixed_header的byte1的高4位 mqtt的类型
        MqttMessageType messageType = MqttMessageType.valueOf(b1 >> 4);
        // byte1的低4位情况
        // 第3位被置位了说明是DUP
        boolean dupFlag = (b1 & 0x08) == 0x08;
        // 第2位和第1位这两位组合起来的值是多少 无非就是0 1 2 3这4种情况
        int qosLevel = (b1 & 0x06) >> 1;
        // 第0位被置位了说明是RETAIN
        boolean retain = (b1 & 0x01) != 0;

        switch (messageType) {
            case PUBLISH:
                if (qosLevel == 3) {
                    throw new DecoderException("Illegal QOS Level in fixed header of PUBLISH message ("
                            + qosLevel + ')');
                }
                break;

            case PUBREL:
            case SUBSCRIBE:
            case UNSUBSCRIBE:
                if (dupFlag) {
                    throw new DecoderException("Illegal BIT 3 in fixed header of " + messageType
                            + " message, must be 0, found 1");
                }
                if (qosLevel != 1) {
                    throw new DecoderException("Illegal QOS Level in fixed header of " + messageType
                            + " message, must be 1, found " + qosLevel);
                }
                if (retain) {
                    throw new DecoderException("Illegal BIT 0 in fixed header of " + messageType
                            + " message, must be 0, found 1");
                }
                break;

            case AUTH:
            case CONNACK:
            case CONNECT:
            case DISCONNECT:
            case PINGREQ:
            case PINGRESP:
            case PUBACK:
            case PUBCOMP:
            case PUBREC:
            case SUBACK:
            case UNSUBACK:
                if (dupFlag) {
                    throw new DecoderException("Illegal BIT 3 in fixed header of " + messageType
                            + " message, must be 0, found 1");
                }
                if (qosLevel != 0) {
                    throw new DecoderException("Illegal BIT 2 or 1 in fixed header of " + messageType
                            + " message, must be 0, found " + qosLevel);
                }
                if (retain) {
                    throw new DecoderException("Illegal BIT 0 in fixed header of " + messageType
                            + " message, must be 0, found 1");
                }
                break;
            default:
                throw new DecoderException("Unknown message type, do not know how to validate fixed header");
        }
        // 解析出来的length结果
        int remainingLength = 0;
        // 解析length的时候第1个字节进制是128的0次方
        int multiplier = 1;
        short digit;
        /**
         * fixed_header里面的byte2编码特点 它不一定刚好仅仅是1个字节 这个字段本身就是变长的 最少1个字节 最多4个字节 怎么知道是不是变长的 在高7位标识
         * 高7位 1表示remain length这个字段需要继续解析后面的字节 0表示这个字段的解析到此为止
         * 低0到低6位 才是真正的长度内容
         * 最多循环看4个字节 看高7位的表示决定要不要继续解析
         */
        int loops = 0;
        // 因为remain length至少占1个字节 所以用do...while 不管什么情况先搞出来1个字节拿出来它的内容 看后再看高7位的标识 决定要不要继续
        do {
            // 先拿出来1个字节
            digit = buffer.readUnsignedByte();
            /**
             * mqtt不是按照10进制存储的 因为最多只能用4个字节表达remain length 而且每个字节只能用7个位
             * 所以mqtt为了28个有效位能表达更大的长度 就用了128进制
             * 第1个字节 n1*128^0
             * 第2个字节 n2*128^1
             * 第3个字节 n3*128^2
             * 第4个字节 n4*128^3
             */
            // 当前这个字节的低7位有效内容拿出来 乘以当前字节的对应的进制
            remainingLength += (digit & 127) * multiplier;
            // 下一个字节的进制在当前进制上成128
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4); // 高7位置1了就继续 上限解析4个字节

        // MQTT protocol limits Remaining Length to 4 bytes
        // 防御性检查 协议规定length这个byte2变成最多4个字节 要是第4个字节的标志位高7位还是1 就说明协议本身就有问题了 不规范
        if (loops == 4 && (digit & 128) != 0) {
            throw new DecoderException("remaining length exceeds 4 digits (" + messageType + ')');
        }
        MqttFixedHeader decodedFixedHeader =
                new MqttFixedHeader(messageType, dupFlag, MqttQoS.valueOf(qosLevel), retain, remainingLength);
        return validateFixedHeader(ctx, resetUnusedFields(decodedFixedHeader));
    }

    /**
     * Decodes the variable header (if any)
     * @param buffer the buffer to decode from
     * @param mqttFixedHeader MqttFixedHeader of the same message 已经解析出来的fixed_header
     * @return the variable header
     */
    private Result<?> decodeVariableHeader(ChannelHandlerContext ctx, ByteBuf buffer, MqttFixedHeader mqttFixedHeader) {
        // message type决定variable header长什么样子
        switch (mqttFixedHeader.messageType()) {
            case CONNECT:
                return decodeConnectionVariableHeader(ctx, buffer);

            case CONNACK:
                return decodeConnAckVariableHeader(ctx, buffer);

            case UNSUBSCRIBE:
            case SUBSCRIBE:
            case SUBACK:
            case UNSUBACK:
                return decodeMessageIdAndPropertiesVariableHeader(ctx, buffer);

            case PUBACK:
            case PUBREC:
            case PUBCOMP:
            case PUBREL:
                return decodePubReplyMessage(buffer);

            case PUBLISH:
                return decodePublishVariableHeader(ctx, buffer, mqttFixedHeader);

            case DISCONNECT:
            case AUTH:
                return decodeReasonCodeAndPropertiesVariableHeader(buffer);

            case PINGREQ:
            case PINGRESP:
                // Empty variable header
                return new Result<Object>(null, 0);
            default:
                //shouldn't reach here
                throw new DecoderException("Unknown message type: " + mqttFixedHeader.messageType());
        }
    }

    /**
     * connect类型的variable header包含的字段有
     *   - Protocol Name 字符串 6个字节
     *                   byte1 byte2 byte3 byte4 byte5 byte6
     *                   MSB   LSB    M      Q     T    T
     *   - Protocol Level 1个字节 值是5表示 Version5
     *   - Connect Flags 1个字节 8位分别是
     *                   Bit        7               6              5            4           3         2            1           0
     *                       User Name Flag | Password Flag | Will Retain |        Will QoS     | Will Flag | Clean Start | Reserved
     *   - Keep Alive 2个字节 整数
     *   - Properties
     * 编字符串的时候先给出字符串长度 再给字符串内容 MSB的高8位 LSB是低8位 组合起来就是字符串长度
     */
    private static Result<MqttConnectVariableHeader> decodeConnectionVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        // 解析出字符串 字段Protocol Name是固定值MQTT
        final Result<String> protoString = decodeString(buffer);
        // 编码MQTT用了6个字节
        int numberOfBytesConsumed = protoString.numberOfBytesConsumed;
        // 编码Protocol Level用1个字节
        final byte protocolLevel = buffer.readByte();
        numberOfBytesConsumed += 1;

        MqttVersion version = MqttVersion.fromProtocolNameAndLevel(protoString.value, protocolLevel);
        MqttCodecUtil.setMqttVersion(ctx, version);
        // flags占1字节
        final int b1 = buffer.readUnsignedByte();
        numberOfBytesConsumed += 1;
        // 2个字节的整数
        final int keepAlive = decodeMsbLsb(buffer);
        numberOfBytesConsumed += 2;
        // flags的高7位有没有被置位
        final boolean hasUserName = (b1 & 0x80) == 0x80;
        // flags的高6位有没有被置位
        final boolean hasPassword = (b1 & 0x40) == 0x40;
        // flags的高5位有没有被置位
        final boolean willRetain = (b1 & 0x20) == 0x20;
        // 拿出来flags字节的低3和低4位的值
        final int willQos = (b1 & 0x18) >> 3;
        // flags的低2位有没有被置位
        final boolean willFlag = (b1 & 0x04) == 0x04;
        // flags的低1位有没有被置位
        final boolean cleanSession = (b1 & 0x02) == 0x02;
        if (version == MqttVersion.MQTT_3_1_1 || version == MqttVersion.MQTT_5) {
            final boolean zeroReservedFlag = (b1 & 0x01) == 0x0;
            if (!zeroReservedFlag) {
                // MQTT v3.1.1: The Server MUST validate that the reserved flag in the CONNECT Control Packet is
                // set to zero and disconnect the Client if it is not zero.
                // See https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc385349230
                throw new DecoderException("non-zero reserved flag");
            }
        }

        final MqttProperties properties;
        if (version == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttConnectVariableHeader mqttConnectVariableHeader = new MqttConnectVariableHeader(
                version.protocolName(),
                version.protocolLevel(),
                hasUserName,
                hasPassword,
                willRetain,
                willQos,
                willFlag,
                cleanSession,
                keepAlive,
                properties);
        return new Result<MqttConnectVariableHeader>(mqttConnectVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttConnAckVariableHeader> decodeConnAckVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final boolean sessionPresent = (buffer.readUnsignedByte() & 0x01) == 0x01;
        byte returnCode = buffer.readByte();
        int numberOfBytesConsumed = 2;

        final MqttProperties properties;
        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(MqttConnectReturnCode.valueOf(returnCode), sessionPresent, properties);
        return new Result<MqttConnAckVariableHeader>(mqttConnAckVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttMessageIdAndPropertiesVariableHeader> decodeMessageIdAndPropertiesVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final int packetId = decodeMessageId(buffer);

        final MqttMessageIdAndPropertiesVariableHeader mqttVariableHeader;
        final int mqtt5Consumed;

        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> properties = decodeProperties(buffer);
            mqttVariableHeader = new MqttMessageIdAndPropertiesVariableHeader(packetId, properties.value);
            mqtt5Consumed = properties.numberOfBytesConsumed;
        } else {
            mqttVariableHeader = new MqttMessageIdAndPropertiesVariableHeader(packetId,
                    MqttProperties.NO_PROPERTIES);
            mqtt5Consumed = 0;
        }

        return new Result<MqttMessageIdAndPropertiesVariableHeader>(mqttVariableHeader,
                2 + mqtt5Consumed);
    }

    private Result<MqttPubReplyMessageVariableHeader> decodePubReplyMessage(ByteBuf buffer) {
        final int packetId = decodeMessageId(buffer);

        final MqttPubReplyMessageVariableHeader mqttPubAckVariableHeader;
        final int consumed;
        final int packetIdNumberOfBytesConsumed = 2;
        if (bytesRemainingInVariablePart > 3) {
            final byte reasonCode = buffer.readByte();
            final Result<MqttProperties> properties = decodeProperties(buffer);
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId,
                    reasonCode,
                    properties.value);
            consumed = packetIdNumberOfBytesConsumed + 1 + properties.numberOfBytesConsumed;
        } else if (bytesRemainingInVariablePart > 2) {
            final byte reasonCode = buffer.readByte();
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId,
                    reasonCode,
                    MqttProperties.NO_PROPERTIES);
            consumed = packetIdNumberOfBytesConsumed + 1;
        } else {
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId,
                    (byte) 0,
                    MqttProperties.NO_PROPERTIES);
            consumed = packetIdNumberOfBytesConsumed;
        }

        return new Result<MqttPubReplyMessageVariableHeader>(mqttPubAckVariableHeader, consumed);
    }

    private Result<MqttReasonCodeAndPropertiesVariableHeader> decodeReasonCodeAndPropertiesVariableHeader(
            ByteBuf buffer) {
        final byte reasonCode;
        final MqttProperties properties;
        final int consumed;
        if (bytesRemainingInVariablePart > 1) {
            reasonCode = buffer.readByte();
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            consumed = 1 + propertiesResult.numberOfBytesConsumed;
        } else if (bytesRemainingInVariablePart > 0) {
            reasonCode = buffer.readByte();
            properties = MqttProperties.NO_PROPERTIES;
            consumed = 1;
        } else {
            reasonCode = 0;
            properties = MqttProperties.NO_PROPERTIES;
            consumed = 0;
        }
        final MqttReasonCodeAndPropertiesVariableHeader mqttReasonAndPropsVariableHeader =
                new MqttReasonCodeAndPropertiesVariableHeader(reasonCode, properties);

        return new Result<MqttReasonCodeAndPropertiesVariableHeader>(
                mqttReasonAndPropsVariableHeader,
                consumed);
    }

    private Result<MqttPublishVariableHeader> decodePublishVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer,
            MqttFixedHeader mqttFixedHeader) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final Result<String> decodedTopic = decodeString(buffer);
        if (!isValidPublishTopicName(decodedTopic.value)) {
            throw new DecoderException("invalid publish topic name: " + decodedTopic.value + " (contains wildcards)");
        }
        int numberOfBytesConsumed = decodedTopic.numberOfBytesConsumed;

        int messageId = -1;
        if (mqttFixedHeader.qosLevel().value() > 0) {
            messageId = decodeMessageId(buffer);
            numberOfBytesConsumed += 2;
        }

        final MqttProperties properties;
        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttPublishVariableHeader mqttPublishVariableHeader =
                new MqttPublishVariableHeader(decodedTopic.value, messageId, properties);
        return new Result<MqttPublishVariableHeader>(mqttPublishVariableHeader, numberOfBytesConsumed);
    }

    /**
     * @return messageId with numberOfBytesConsumed is 2
     */
    private static int decodeMessageId(ByteBuf buffer) {
        final int messageId = decodeMsbLsb(buffer);
        if (!isValidMessageId(messageId)) {
            throw new DecoderException("invalid messageId: " + messageId);
        }
        return messageId;
    }

    /**
     * Decodes the payload.
     *
     * @param buffer the buffer to decode from
     * @param messageType  type of the message being decoded
     * @param bytesRemainingInVariablePart bytes remaining 现在字节流里面有多少字节是payload的
     * @param variableHeader variable header of the same message
     * @return the payload
     */
    private static Result<?> decodePayload(
            ChannelHandlerContext ctx,
            ByteBuf buffer,
            MqttMessageType messageType,
            int bytesRemainingInVariablePart,
            int maxClientIdLength,
            Object variableHeader) {
        switch (messageType) {
            case CONNECT:
                return decodeConnectionPayload(buffer, maxClientIdLength, (MqttConnectVariableHeader) variableHeader);

            case SUBSCRIBE:
                return decodeSubscribePayload(buffer, bytesRemainingInVariablePart);

            case SUBACK:
                return decodeSubackPayload(buffer, bytesRemainingInVariablePart);

            case UNSUBSCRIBE:
                return decodeUnsubscribePayload(buffer, bytesRemainingInVariablePart);

            case UNSUBACK:
                return decodeUnsubAckPayload(ctx, buffer, bytesRemainingInVariablePart);

            case PUBLISH:
                return decodePublishPayload(buffer, bytesRemainingInVariablePart);

            default:
                // unknown payload , no byte consumed
                return new Result<Object>(null, 0);
        }
    }

    private static Result<MqttConnectPayload> decodeConnectionPayload(
            ByteBuf buffer,
            int maxClientIdLength,
            MqttConnectVariableHeader mqttConnectVariableHeader) {
        final Result<String> decodedClientId = decodeString(buffer);
        final String decodedClientIdValue = decodedClientId.value;
        final MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(mqttConnectVariableHeader.name(),
                (byte) mqttConnectVariableHeader.version());
        if (!isValidClientId(mqttVersion, maxClientIdLength, decodedClientIdValue)) {
            throw new MqttIdentifierRejectedException("invalid clientIdentifier: " + decodedClientIdValue);
        }
        int numberOfBytesConsumed = decodedClientId.numberOfBytesConsumed;

        Result<String> decodedWillTopic = null;
        byte[] decodedWillMessage = null;

        final MqttProperties willProperties;
        if (mqttConnectVariableHeader.isWillFlag()) {
            if (mqttVersion == MqttVersion.MQTT_5) {
                final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
                willProperties = propertiesResult.value;
                numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
            } else {
                willProperties = MqttProperties.NO_PROPERTIES;
            }
            decodedWillTopic = decodeString(buffer, 0, 32767);
            numberOfBytesConsumed += decodedWillTopic.numberOfBytesConsumed;
            decodedWillMessage = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedWillMessage.length + 2;
        } else {
            willProperties = MqttProperties.NO_PROPERTIES;
        }
        Result<String> decodedUserName = null;
        byte[] decodedPassword = null;
        if (mqttConnectVariableHeader.hasUserName()) {
            decodedUserName = decodeString(buffer);
            numberOfBytesConsumed += decodedUserName.numberOfBytesConsumed;
        }
        if (mqttConnectVariableHeader.hasPassword()) {
            decodedPassword = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedPassword.length + 2;
        }

        final MqttConnectPayload mqttConnectPayload =
                new MqttConnectPayload(
                        decodedClientId.value,
                        willProperties,
                        decodedWillTopic != null ? decodedWillTopic.value : null,
                        decodedWillMessage,
                        decodedUserName != null ? decodedUserName.value : null,
                        decodedPassword);
        return new Result<MqttConnectPayload>(mqttConnectPayload, numberOfBytesConsumed);
    }

    private static Result<MqttSubscribePayload> decodeSubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<MqttTopicSubscription> subscribeTopics = new ArrayList<MqttTopicSubscription>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            //See 3.8.3.1 Subscription Options of MQTT 5.0 specification for optionByte details
            final short optionByte = buffer.readUnsignedByte();

            MqttQoS qos = MqttQoS.valueOf(optionByte & 0x03);
            boolean noLocal = ((optionByte & 0x04) >> 2) == 1;
            boolean retainAsPublished = ((optionByte & 0x08) >> 3) == 1;
            RetainedHandlingPolicy retainHandling = RetainedHandlingPolicy.valueOf((optionByte & 0x30) >> 4);

            final MqttSubscriptionOption subscriptionOption = new MqttSubscriptionOption(qos,
                    noLocal,
                    retainAsPublished,
                    retainHandling);

            numberOfBytesConsumed++;
            subscribeTopics.add(new MqttTopicSubscription(decodedTopicName.value, subscriptionOption));
        }
        return new Result<MqttSubscribePayload>(new MqttSubscribePayload(subscribeTopics), numberOfBytesConsumed);
    }

    private static Result<MqttSubAckPayload> decodeSubackPayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<Integer> grantedQos = new ArrayList<Integer>(bytesRemainingInVariablePart);
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            int reasonCode = buffer.readUnsignedByte();
            numberOfBytesConsumed++;
            grantedQos.add(reasonCode);
        }
        return new Result<MqttSubAckPayload>(new MqttSubAckPayload(grantedQos), numberOfBytesConsumed);
    }

    private static Result<MqttUnsubAckPayload> decodeUnsubAckPayload(
        ChannelHandlerContext ctx,
        ByteBuf buffer,
        int bytesRemainingInVariablePart) {
        final List<Short> reasonCodes = new ArrayList<Short>(bytesRemainingInVariablePart);
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            short reasonCode = buffer.readUnsignedByte();
            numberOfBytesConsumed++;
            reasonCodes.add(reasonCode);
        }
        return new Result<MqttUnsubAckPayload>(new MqttUnsubAckPayload(reasonCodes), numberOfBytesConsumed);
    }

    private static Result<MqttUnsubscribePayload> decodeUnsubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<String> unsubscribeTopics = new ArrayList<String>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            unsubscribeTopics.add(decodedTopicName.value);
        }
        return new Result<MqttUnsubscribePayload>(
                new MqttUnsubscribePayload(unsubscribeTopics),
                numberOfBytesConsumed);
    }

    private static Result<ByteBuf> decodePublishPayload(ByteBuf buffer, int bytesRemainingInVariablePart) {
        ByteBuf b = buffer.readRetainedSlice(bytesRemainingInVariablePart);
        return new Result<ByteBuf>(b, bytesRemainingInVariablePart);
    }

    /**
     * mqtt对字符串的编码有两部分 字符串长度+字符串内容 字符串长度用2个字节
     * 第1个字节是MSB是长度的高8位
     * 第2个字节是LSB是长度的低8位
     * 组合起来就是字符串的长度
     *
     * byte1 byte2    bytes
     * MSB   LSB    字符串内容
     * @param buffer
     * @return
     */
    private static Result<String> decodeString(ByteBuf buffer) {
        return decodeString(buffer, 0, Integer.MAX_VALUE);
    }


    /**
     * 从字节流里面读字符串
     * @param buffer
     * @param minBytes
     * @param maxBytes
     * @return
     */
    private static Result<String> decodeString(ByteBuf buffer, int minBytes, int maxBytes) {
        // 字符串的长度有多长
        int size = decodeMsbLsb(buffer);
        // 在编码字符串的时候先用2个字节表示MSB和LSB
        int numberOfBytesConsumed = 2;
        if (size < minBytes || size > maxBytes) {
            buffer.skipBytes(size);
            numberOfBytesConsumed += size;
            return new Result<String>(null, numberOfBytesConsumed);
        }
        String s = buffer.toString(buffer.readerIndex(), size, CharsetUtil.UTF_8);
        buffer.skipBytes(size);
        // 又用了具体多少个长度编码真正的内容
        numberOfBytesConsumed += size;
        return new Result<String>(s, numberOfBytesConsumed);
    }

    /**
     *
     * @return the decoded byte[], numberOfBytesConsumed = byte[].length + 2
     */
    private static byte[] decodeByteArray(ByteBuf buffer) {
        int size = decodeMsbLsb(buffer);
        byte[] bytes = new byte[size];
        buffer.readBytes(bytes);
        return bytes;
    }

    // packing utils to reduce the amount of garbage while decoding ints
    private static long packInts(int a, int b) {
        return (((long) a) << 32) | (b & 0xFFFFFFFFL);
    }

    private static int unpackA(long ints) {
        return (int) (ints >> 32);
    }

    private static int unpackB(long ints) {
        return (int) ints;
    }

    /**
     *  numberOfBytesConsumed = 2. return decoded result.
     *  从字节流里面拿出2个字节
     *  第1个字节是MSB表示字符串长度的高8位
     *  第2个字节是LSB表示字符串长度的低8位
     *  组合起来的16位整数大小
     * @return 16位的整数
     */
    private static int decodeMsbLsb(ByteBuf buffer) {
        int min = 0;
        int max = 65535;
        // 拿出第1个字节是msb
        short msbSize = buffer.readUnsignedByte();
        // 拿出第2个字节是lsb
        short lsbSize = buffer.readUnsignedByte();
        // msb是高8位 lsb是低8位 组合起来 就是字符串长度
        int result = msbSize << 8 | lsbSize;
        if (result < min || result > max) {
            result = -1;
        }
        return result;
    }

    /**
     * See 1.5.5 Variable Byte Integer section of MQTT 5.0 specification for encoding/decoding rules
     *
     * @param buffer the buffer to decode from
     * @return result pack with a = decoded integer, b = numberOfBytesConsumed. Need to unpack to read them.
     * @throws DecoderException if bad MQTT protocol limits Remaining Length
     */
    private static long decodeVariableByteInteger(ByteBuf buffer) {
        int remainingLength = 0;
        int multiplier = 1;
        short digit;
        int loops = 0;
        do {
            digit = buffer.readUnsignedByte();
            remainingLength += (digit & 127) * multiplier;
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4);

        if (loops == 4 && (digit & 128) != 0) {
            throw new DecoderException("MQTT protocol limits Remaining Length to 4 bytes");
        }
        return packInts(remainingLength, loops);
    }

    private static final class Result<T> {

        private final T value;
        // 编码value这个内容一共在字节流里面占了多少个字节
        private final int numberOfBytesConsumed;

        Result(T value, int numberOfBytesConsumed) {
            this.value = value;
            this.numberOfBytesConsumed = numberOfBytesConsumed;
        }
    }

    private static Result<MqttProperties> decodeProperties(ByteBuf buffer) {
        final long propertiesLength = decodeVariableByteInteger(buffer);
        int totalPropertiesLength = unpackA(propertiesLength);
        int numberOfBytesConsumed = unpackB(propertiesLength);

        MqttProperties decodedProperties = new MqttProperties();
        while (numberOfBytesConsumed < totalPropertiesLength) {
            long propertyId = decodeVariableByteInteger(buffer);
            final int propertyIdValue = unpackA(propertyId);
            numberOfBytesConsumed += unpackB(propertyId);
            MqttProperties.MqttPropertyType propertyType = MqttProperties.MqttPropertyType.valueOf(propertyIdValue);
            switch (propertyType) {
                case PAYLOAD_FORMAT_INDICATOR:
                case REQUEST_PROBLEM_INFORMATION:
                case REQUEST_RESPONSE_INFORMATION:
                case MAXIMUM_QOS:
                case RETAIN_AVAILABLE:
                case WILDCARD_SUBSCRIPTION_AVAILABLE:
                case SUBSCRIPTION_IDENTIFIER_AVAILABLE:
                case SHARED_SUBSCRIPTION_AVAILABLE:
                    final int b1 = buffer.readUnsignedByte();
                    numberOfBytesConsumed++;
                    decodedProperties.add(new IntegerProperty(propertyIdValue, b1));
                    break;
                case SERVER_KEEP_ALIVE:
                case RECEIVE_MAXIMUM:
                case TOPIC_ALIAS_MAXIMUM:
                case TOPIC_ALIAS:
                    final int int2BytesResult = decodeMsbLsb(buffer);
                    numberOfBytesConsumed += 2;
                    decodedProperties.add(new IntegerProperty(propertyIdValue, int2BytesResult));
                    break;
                case PUBLICATION_EXPIRY_INTERVAL:
                case SESSION_EXPIRY_INTERVAL:
                case WILL_DELAY_INTERVAL:
                case MAXIMUM_PACKET_SIZE:
                    final int maxPacketSize = buffer.readInt();
                    numberOfBytesConsumed += 4;
                    decodedProperties.add(new IntegerProperty(propertyIdValue, maxPacketSize));
                    break;
                case SUBSCRIPTION_IDENTIFIER:
                    long vbIntegerResult = decodeVariableByteInteger(buffer);
                    numberOfBytesConsumed += unpackB(vbIntegerResult);
                    decodedProperties.add(new IntegerProperty(propertyIdValue, unpackA(vbIntegerResult)));
                    break;
                case CONTENT_TYPE:
                case RESPONSE_TOPIC:
                case ASSIGNED_CLIENT_IDENTIFIER:
                case AUTHENTICATION_METHOD:
                case RESPONSE_INFORMATION:
                case SERVER_REFERENCE:
                case REASON_STRING:
                    final Result<String> stringResult = decodeString(buffer);
                    numberOfBytesConsumed += stringResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.StringProperty(propertyIdValue, stringResult.value));
                    break;
                case USER_PROPERTY:
                    final Result<String> keyResult = decodeString(buffer);
                    final Result<String> valueResult = decodeString(buffer);
                    numberOfBytesConsumed += keyResult.numberOfBytesConsumed;
                    numberOfBytesConsumed += valueResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.UserProperty(keyResult.value, valueResult.value));
                    break;
                case CORRELATION_DATA:
                case AUTHENTICATION_DATA:
                    final byte[] binaryDataResult = decodeByteArray(buffer);
                    numberOfBytesConsumed += binaryDataResult.length + 2;
                    decodedProperties.add(new MqttProperties.BinaryProperty(propertyIdValue, binaryDataResult));
                    break;
                default:
                    //shouldn't reach here
                    throw new DecoderException("Unknown property type: " + propertyType);
            }
        }

        return new Result<MqttProperties>(decodedProperties, numberOfBytesConsumed);
    }
}
