/*
 * Copyright 2024 The Netty Project
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
package io.netty.channel.uring;

import io.netty.util.internal.PlatformDependent;

import java.util.StringJoiner;

/**
 * Completion queue implementation for io_uring.
 */
final class CompletionQueue {

    //these offsets are used to access specific properties
    //CQE (https://github.com/axboe/liburing/blob/master/src/include/liburing/io_uring.h#L162)
    private static final int CQE_USER_DATA_FIELD = 0;
    private static final int CQE_RES_FIELD = 8;
    private static final int CQE_FLAGS_FIELD = 12;

    private static final long CQE_SIZE = 16;

    //these unsigned integer pointers(shared with the kernel) will be changed by the kernel
    private final long kHeadAddress;
    private final long kTailAddress;

    private final long completionQueueArrayAddress;

    final int ringSize;
    final long ringAddress;
    final int ringFd;
    final int ringEntries;
    final int ringCapacity;

    private final int ringMask;
    private int ringHead;
    private boolean closed;

    CompletionQueue(long kHeadAddress, long kTailAddress, int ringMask, int ringEntries,
                    long completionQueueArrayAddress, int ringSize, long ringAddress,
                    int ringFd, int ringCapacity) {
        this.kHeadAddress = kHeadAddress;
        this.kTailAddress = kTailAddress;
        this.completionQueueArrayAddress = completionQueueArrayAddress;
        this.ringSize = ringSize;
        this.ringAddress = ringAddress;
        this.ringFd = ringFd;
        this.ringCapacity = ringCapacity;

        this.ringEntries = ringEntries;
        this.ringMask = ringMask;
        ringHead = PlatformDependent.getIntVolatile(kHeadAddress);
    }

    void close() {
        closed = true;
    }

    /**
     * Returns {@code true} if any completion event is ready to be processed by
     * {@link #process(CompletionCallback)}, {@code false} otherwise.
     */
    boolean hasCompletions() {
        return !closed && ringHead != PlatformDependent.getIntVolatile(kTailAddress);
    }

    int count() {
        if (closed) {
            return 0;
        }
        return PlatformDependent.getIntVolatile(kTailAddress) - ringHead;
    }

    /**
     * Process the completion events in the {@link CompletionQueue} and return the number of processed
     * events.
     */
    int process(CompletionCallback callback) {
        if (closed) {
            return 0;
        }
        int tail = PlatformDependent.getIntVolatile(kTailAddress);
        try {
            int i = 0;
            while (ringHead != tail) {
                long cqeAddress = completionQueueArrayAddress + (ringHead & ringMask) * CQE_SIZE;

                long udata = PlatformDependent.getLong(cqeAddress + CQE_USER_DATA_FIELD);
                int res = PlatformDependent.getInt(cqeAddress + CQE_RES_FIELD);
                int flags = PlatformDependent.getInt(cqeAddress + CQE_FLAGS_FIELD);

                ringHead++;

                i++;
                if (!callback.handle(res, flags, udata)) {
                    // Stop processing. as the callback can not handle any more completions for now,
                    break;
                }
            }
            return i;
        } finally {
            // Ensure that the kernel only sees the new value of the head index after the CQEs have been read.
            PlatformDependent.putIntOrdered(kHeadAddress, ringHead);
        }
    }

    @Override
    public String toString() {
        StringJoiner sb = new StringJoiner(", ", "CompletionQueue [", "]");
        if (closed) {
            sb.add("closed");
        } else {
            int tail = PlatformDependent.getIntVolatile(kTailAddress);
            int head = ringHead;
            while (head != tail) {
                long cqeAddress = completionQueueArrayAddress + (ringHead & ringMask) * CQE_SIZE;
                long udata = PlatformDependent.getLong(cqeAddress + CQE_USER_DATA_FIELD);
                int res = PlatformDependent.getInt(cqeAddress + CQE_RES_FIELD);
                int flags = PlatformDependent.getInt(cqeAddress + CQE_FLAGS_FIELD);

                sb.add("(res=" + res).add(", flags=" + flags).add(", udata=" + udata).add(")");
                head++;
            }
        }
        return sb.toString();
    }
}
