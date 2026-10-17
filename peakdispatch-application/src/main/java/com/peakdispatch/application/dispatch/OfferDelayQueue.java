package com.peakdispatch.application.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Disposable Redis delay index for Offer expiry; MySQL remains authoritative. */
public interface OfferDelayQueue {
    void schedule(UUID offerId, Instant dueAt);

    List<DueOffer> claimDue(int limit, Instant now);

    void complete(DueOffer offer);

    void release(DueOffer offer, Instant retryAt);

    record DueOffer(UUID offerId, Instant dueAt, String claimToken) {
    }
}
