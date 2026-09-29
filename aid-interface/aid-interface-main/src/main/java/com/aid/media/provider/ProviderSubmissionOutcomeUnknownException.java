package com.aid.media.provider;

/** A request may already have been accepted upstream. Preserve the task and its frozen balance for reconciliation. */
public class ProviderSubmissionOutcomeUnknownException extends RuntimeException {
    public ProviderSubmissionOutcomeUnknownException() {
        super("上游提交状态待核");
    }
}
