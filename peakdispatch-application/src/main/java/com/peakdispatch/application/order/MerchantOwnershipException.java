package com.peakdispatch.application.order;

import java.util.UUID;

public class MerchantOwnershipException extends RuntimeException {
    private final UUID merchantId;
    private final UUID orderId;

    public MerchantOwnershipException(UUID merchantId, UUID orderId) {
        super("merchant is not allowed to update this order");
        this.merchantId = merchantId;
        this.orderId = orderId;
    }

    public UUID merchantId() { return merchantId; }
    public UUID orderId() { return orderId; }
}
