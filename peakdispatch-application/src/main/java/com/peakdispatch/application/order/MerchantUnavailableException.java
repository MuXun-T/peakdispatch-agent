package com.peakdispatch.application.order;

import java.util.UUID;

public class MerchantUnavailableException extends RuntimeException {
    private final UUID merchantId;
    private final String regionId;

    public MerchantUnavailableException(UUID merchantId, String regionId) {
        super("merchant is not active in the requested region");
        this.merchantId = merchantId;
        this.regionId = regionId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String regionId() {
        return regionId;
    }
}
