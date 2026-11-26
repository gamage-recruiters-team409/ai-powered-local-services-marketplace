package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.port.LateStartsPort;

/**
 * Stub late-starts source: WP05 exposes no late-start query, so this always reports {@code available = false} with no
 * rows and no numbers. Replace with a real adapter once WP05 adds the query (see the gaps doc).
 */
@Component
public class StubLateStartsAdapter implements LateStartsPort {

    @Override
    public Result findLateStarts(int limit) {
        return Result.unavailable(OperationsRules.LATE_STARTS_UNAVAILABLE);
    }
}
