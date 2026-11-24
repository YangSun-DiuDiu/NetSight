package com.netsight.common.exception;

import lombok.Getter;

@Getter
public class StaleRevisionException extends RuntimeException {
    private final long currentRevision;

    public StaleRevisionException(long currentRevision) {
        super("STALE_REVISION");
        this.currentRevision = currentRevision;
    }
}
