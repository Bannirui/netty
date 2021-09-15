/*
 * Copyright 2021 The Netty Project
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

package io.netty.handler.codec.compression;

import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.internal.ObjectUtil;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Uncompresses a {@link ByteBuf} encoded with the brotli format.
 *
 * See <a href="https://github.com/google/brotli">brotli</a>.
 */
public final class BrotliDecoder extends DecompressionHandler {

    /**
     * Creates a new BrotliDecoder with a default 8kB input buffer
     */
    public BrotliDecoder() {
        this(8 * 1024);
    }

    /**
     * Creates a new BrotliDecoder
     * @param inputBufferSize desired size of the input buffer in bytes
     */
    public BrotliDecoder(int inputBufferSize) {
        super(() -> {
            try {
                return new BrotliDecompressor(inputBufferSize);
            } catch (IOException e) {
                throw new DecompressionException(e);
            }
        });
        ObjectUtil.checkPositive(inputBufferSize, "inputBufferSize");
    }

    private static final class BrotliDecompressor implements Decompressor {

        static {
            try {
                Brotli.ensureAvailability();
            } catch (Throwable throwable) {
                throw new ExceptionInInitializerError(throwable);
            }
        }

        private boolean finished;
        private final DecoderJNI.Wrapper decoder;

        /**
         * Creates a new BrotliDecoder with a default 8kB input buffer
         */
        BrotliDecompressor() throws IOException {
            this(8 * 1024);
        }

        /**
         * Creates a new BrotliDecoder
         * @param inputBufferSize desired size of the input buffer in bytes
         */
        BrotliDecompressor(int inputBufferSize) throws IOException {
            decoder = new DecoderJNI.Wrapper(ObjectUtil.checkPositive(inputBufferSize, "inputBufferSize"));
        }

        private ByteBuf pull(ByteBufAllocator alloc) {
            ByteBuffer nativeBuffer = decoder.pull();
            // nativeBuffer actually wraps brotli's internal buffer so we need to copy its content
            ByteBuf copy = alloc.buffer(nativeBuffer.remaining());
            copy.writeBytes(nativeBuffer);
            return copy;
        }

        private static int readBytes(ByteBuf in, ByteBuffer dest) {
            int limit = Math.min(in.readableBytes(), dest.remaining());
            ByteBuffer slice = dest.slice();
            slice.limit(limit);
            in.readBytes(slice);
            dest.position(dest.position() + limit);
            return limit;
        }

        @Override
        public ByteBuf decompress(ByteBuf input, ByteBufAllocator allocator) throws DecompressionException {
            if (finished) {
                return null;
            }

            for (;;) {
                switch (decoder.getStatus()) {
                    case DONE:
                        finished = true;
                        decoder.destroy();
                        return null;

                    case OK:
                        decoder.push(0);
                        break;

                    case NEEDS_MORE_INPUT:
                        if (decoder.hasOutput()) {
                            return pull(allocator);
                        }

                        if (!input.isReadable()) {
                            return null;
                        }

                        ByteBuffer decoderInputBuffer = decoder.getInputBuffer();
                        decoderInputBuffer.clear();
                        int readBytes = readBytes(input, decoderInputBuffer);
                        decoder.push(readBytes);
                        break;

                    case NEEDS_MORE_OUTPUT:
                        return pull(allocator);
                    default:
                        finished = true;
                        decoder.destroy();
                        throw new DecompressionException("Brotli stream corrupted");
                }
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public void close() {
            if (!finished) {
                finished = true;
                decoder.destroy();
            }
        }
    }
}
