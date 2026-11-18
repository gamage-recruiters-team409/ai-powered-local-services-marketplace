package com.gamagerecruiters.marketplace.modules.dispute.port;

import java.util.Optional;

/** Display names for the admin dispute screens. Names only: no email, phone or other account data. */
public interface UserDirectoryPort {

    /** The user's display name, or empty if it cannot be read (unknown user, lookup failure). Never throws. */
    Optional<String> findDisplayName(String userId);
}
