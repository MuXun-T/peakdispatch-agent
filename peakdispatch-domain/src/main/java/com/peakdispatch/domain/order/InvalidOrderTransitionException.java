package com.peakdispatch.domain.order;

public class InvalidOrderTransitionException extends RuntimeException {
    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("invalid order transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() { return from; }
    public OrderStatus to() { return to; }
}
