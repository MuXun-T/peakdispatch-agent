package com.peakdispatch.config;

import com.peakdispatch.application.dispatch.DispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'API'")
public class DispatchWorker {
    private final DispatchService dispatch;

    public DispatchWorker(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.lifecycle-delay-ms:60000}")
    public void advanceCreatedOrders() {
        dispatch.advanceCreatedOrders();
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.dispatch-delay-ms:60000}")
    public void dispatchWaitingOrders() {
        dispatch.dispatchWaitingOrders();
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.offer-timeout-delay-ms:1000}")
    public void expireOffers() {
        dispatch.expireOffers();
    }
}
