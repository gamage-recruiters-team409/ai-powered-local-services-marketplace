package com.gamagerecruiters.marketplace.modules.auth.service;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;

public interface EmailTokenService {

    void sendVerificationEmail(User user);

    void verifyEmail(String rawToken);

    void resendVerification(String email);

    void forgotPassword(String email);

    void resetPassword(String rawToken, String newPassword);
}
