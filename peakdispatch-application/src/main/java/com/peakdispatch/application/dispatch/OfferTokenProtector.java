package com.peakdispatch.application.dispatch;

public interface OfferTokenProtector {
    String protect(String token);
    String reveal(String ciphertext);
}
