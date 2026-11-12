package com.flowpilot.messaging.outbox;

import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Claims the outbox rows for publishing.
 *
 * <p>The claim lives in its own bean for a reason: {@link OutboxPublisher} calls it, and a
 * {@code @Transactional} method only gets a transaction when it is called <em>through the
 * Spring proxy</em>. A self invocation would bypass the proxy, the transaction would never
 * start, and the {@code FOR UPDATE SKIP LOCKED} of the claim would hold the row locks until the
 * end of the request — or longer, depending on the connection pool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final MessageOutboxMapper outboxMapper;

    /**
     * Claims rows with a short transaction, then publishes them outside of any transaction so the
     * database connection is not held while waiting for the broker.
     *
     * @param now the moment the claim happens
     * @param limit how many rows are claimed at most
     * @return the claimed rows
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<MessageOutbox> claimBatch(LocalDateTime now, int limit) {
        List<MessageOutbox> candidates = outboxMapper.claimPending(now, limit);
        return candidates.stream()
                .filter(record -> outboxMapper.markPublishing(record.getId(), now) == 1)
                .toList();
    }

    /**
     * Returns the rows whose publisher died to the pending state.
     *
     * @param staleBefore the rows that were claimed before this moment are stale
     * @param limit how many rows are recovered at most
     * @return the recovered rows
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<MessageOutbox> recoverStale(LocalDateTime staleBefore, int limit) {
        List<MessageOutbox> stale = outboxMapper.findStalePublishing(staleBefore, limit);
        for (MessageOutbox record : stale) {
            outboxMapper.reschedule(record.getId(), LocalDateTime.now());
            log.warn("recovered stale outbox row id={} messageId={} publishingAt={}",
                    record.getId(), record.getMessageId(), record.getPublishingAt());
        }
        return stale;
    }
}
