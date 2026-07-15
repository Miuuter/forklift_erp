package com.example.forklift_erp.constant;

/**
 * Immutable accounting event categories. Amounts are signed: a reversal is a
 * new event with the opposite amount, never an in-place update.
 */
public final class FinancialEventType {
    public static final String ACCOUNTS_RECEIVABLE = "ACCOUNTS_RECEIVABLE";
    public static final String ACCOUNTS_PAYABLE = "ACCOUNTS_PAYABLE";
    public static final String REVENUE = "REVENUE";
    public static final String COST_OF_GOODS_SOLD = "COST_OF_GOODS_SOLD";
    public static final String OPERATING_COST = "OPERATING_COST";
    public static final String INVENTORY_GAIN = "INVENTORY_GAIN";
    public static final String INVENTORY_LOSS = "INVENTORY_LOSS";
    public static final String CASH_RECEIPT = "CASH_RECEIPT";
    public static final String CASH_PAYMENT = "CASH_PAYMENT";

    private FinancialEventType() {
    }
}
