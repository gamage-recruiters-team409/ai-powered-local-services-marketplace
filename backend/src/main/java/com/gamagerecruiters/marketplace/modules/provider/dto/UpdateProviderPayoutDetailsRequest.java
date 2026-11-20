package com.gamagerecruiters.marketplace.modules.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateProviderPayoutDetailsRequest {

    @NotBlank(message = "Bank name is required")
    @Size(max = 100, message = "Bank name must not exceed 100 characters")
    private String bankName;

    @NotBlank(message = "Account holder name is required")
    @Size(max = 150, message = "Account holder name must not exceed 150 characters")
    private String accountHolderName;

    @NotBlank(message = "Account number is required")
    @Size(min = 6, max = 34, message = "Account number must contain between 6 and 34 characters")
    @Pattern(
            regexp = "^[0-9][0-9\\s-]{4,32}[0-9]$",
            message = "Account number may contain only digits, spaces, and hyphens"
    )
    private String accountNumber;

    public UpdateProviderPayoutDetailsRequest() {
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public void setAccountHolderName(String accountHolderName) {
        this.accountHolderName = accountHolderName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
}
