package com.gamagerecruiters.marketplace.modules.provider.service;

/**
 * Internal provider payout data for trusted backend module integration.
 *
 * This object contains the full account number and must never be returned
 * directly from a REST controller or written to application logs.
 */
public final class ProviderPayoutDetails {

    private final String bankName;
    private final String accountHolderName;
    private final String accountNumber;

    public ProviderPayoutDetails(
            String bankName,
            String accountHolderName,
            String accountNumber
    ) {
        this.bankName = bankName;
        this.accountHolderName = accountHolderName;
        this.accountNumber = accountNumber;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    @Override
    public String toString() {
        String masked = accountNumber == null || accountNumber.length() <= 4
                ? "****"
                : "****" + accountNumber.substring(accountNumber.length() - 4);

        return "ProviderPayoutDetails[bankName=" + bankName
                + ", accountHolderName=[REDACTED]"
                + ", accountNumber=" + masked + "]";
    }
}
