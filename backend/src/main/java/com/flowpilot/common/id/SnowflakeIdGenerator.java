package com.flowpilot.common.id;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * Snowflake style id generator.
 *
 * <p>All primary keys are produced by the application so the system never depends on database
 * auto increment, which keeps the id space stable across shards and lets a worker create a
 * business row and its message in one transaction.
 *
 * <p>Layout: 41 bit timestamp | 10 bit worker id | 12 bit sequence.
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        this.workerId = resolveWorkerId();
    }

    public long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // Clock moved backwards; wait until the clock catches up rather than emitting
            // duplicate ids.
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                waitUntil(lastTimestamp);
                timestamp = System.currentTimeMillis();
            } else {
                throw new IllegalStateException("Clock moved backwards by " + offset + "ms");
            }
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < target) {
            Thread.onSpinWait();
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private long resolveWorkerId() {
        try {
            InetAddress address = InetAddress.getLocalHost();
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            byte[] mac = network == null ? null : network.getHardwareAddress();
            if (mac != null && mac.length >= 2) {
                return ((0x000000FF & (long) mac[mac.length - 1]) | (0x0000FF00 & ((long) mac[mac.length - 2] << 8))) >> 6
                        & MAX_WORKER_ID;
            }
        } catch (UnknownHostException | java.net.SocketException ignored) {
            // fall through to the random worker id below
        }
        return (long) (Math.random() * MAX_WORKER_ID);
    }

    /**
     * Exposed for diagnostics and tests.
     */
    public long getWorkerId() {
        return workerId;
    }
}
