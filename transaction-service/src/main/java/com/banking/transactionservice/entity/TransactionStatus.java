package com.banking.transactionservice.entity;

/*
    Transaction life cycle flow
    pending => processing => completed (clean transaction)
                             pending verification (suspicious detected)
                                 completed (verified)
                                 flagged (saga refund)
                             failed ()
                             flagged()
 */

public enum TransactionStatus {

    PENDING,
    PROCESSING,
    PENDING_VERIFICATION,
    COMPLETED,
    FAILED,
    FLAGGED
}
