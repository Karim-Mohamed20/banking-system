package com.banking.accountservice.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AccountType {
    SAVINGS,
    CURRENT,
    FIXED_DEPOSIT;

    @JsonCreator
    public static AccountType fromJson(String value) {
        for (AccountType accountType : values()) {
            if (accountType.name().equalsIgnoreCase(value)) {
                return accountType;
            }
        }
        throw new IllegalArgumentException("Unknown account type: " + value);
    }

}
