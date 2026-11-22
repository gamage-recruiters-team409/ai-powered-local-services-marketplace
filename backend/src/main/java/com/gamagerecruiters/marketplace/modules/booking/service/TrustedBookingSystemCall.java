package com.gamagerecruiters.marketplace.modules.booking.service;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Explicit trust boundary for booking changes made by internal jobs (recorded as SYSTEM).
 *
 * <p>An internal job declares itself by running its booking call inside {@link #run}. For that call
 * only, the thread's security context holds a {@link SystemCallAuthentication} with the stated purpose.
 * BookingFacade accepts SYSTEM only when that authentication is present with the expected purpose –
 * never merely because no HTTP request or user is bound to the thread.</p>
 *
 * <p>{@link #run} refuses to start inside an HTTP request or when a user is already authenticated,
 * so a request path cannot raise itself to SYSTEM. Every trusted call is logged with its purpose.</p>
 *
 * <pre>{@code
 * // WP09 settlement job
 * TrustedBookingSystemCall.run(Purpose.DISPUTE_SETTLEMENT,
 *         () -> bookingFacade.resolveDispute(bookingId, finalStatus, idempotencyKey));
 * }</pre>
 */
public final class TrustedBookingSystemCall {

    private static final Logger log = LoggerFactory.getLogger(TrustedBookingSystemCall.class);

    /** The internal operations allowed to change bookings as SYSTEM. */
    public enum Purpose {
        /** WP09 dispute settlement job applying a final dispute decision. */
        DISPUTE_SETTLEMENT
    }

    private TrustedBookingSystemCall() {
    }

    /**
     * Runs {@code action} as a trusted internal SYSTEM call for {@code purpose}, then restores the
     * previous (empty) security context, also when the action throws.
     *
     * @throws IllegalStateException inside an HTTP request or when a user is already authenticated
     */
    public static <T> T run(Purpose purpose, Supplier<T> action) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(action, "action");

        if (RequestContextHolder.getRequestAttributes() != null) {
            throw new IllegalStateException("Trusted system calls cannot run inside an HTTP request");
        }
        SecurityContext previous = SecurityContextHolder.getContext();
        Authentication current = previous.getAuthentication();
        if (current != null && current.isAuthenticated()) {
            throw new IllegalStateException("Trusted system calls cannot run on behalf of an authenticated user");
        }

        SecurityContext systemContext = SecurityContextHolder.createEmptyContext();
        systemContext.setAuthentication(new SystemCallAuthentication(purpose));
        SecurityContextHolder.setContext(systemContext);
        log.info("Trusted booking system call started: purpose={}", purpose);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** True only inside {@link #run} for exactly this purpose. */
    static boolean isActive(Purpose purpose) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof SystemCallAuthentication system
                && system.getPurpose() == purpose
                && system.isAuthenticated();
    }

    /**
     * Security-context marker for a trusted internal call. Not a user: it has no user ID and no
     * roles, so request authorization and CurrentUser never treat it as a logged-in user.
     */
    static final class SystemCallAuthentication extends AbstractAuthenticationToken {

        private final Purpose purpose;

        private SystemCallAuthentication(Purpose purpose) {
            super(List.of());
            this.purpose = purpose;
            super.setAuthenticated(true);
        }

        Purpose getPurpose() {
            return purpose;
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return "SYSTEM:" + purpose;
        }

        @Override
        public void setAuthenticated(boolean authenticated) {
            if (authenticated) {
                throw new IllegalArgumentException("A system call authentication can only be created by run()");
            }
            super.setAuthenticated(false);
        }
    }
}
