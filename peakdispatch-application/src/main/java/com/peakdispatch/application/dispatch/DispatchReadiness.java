package com.peakdispatch.application.dispatch;

/** Shared gate that keeps dispatch paused while Redis projections are rebuilt. */
public interface DispatchReadiness {
    boolean ready();

    void pause();

    void resume();
}
