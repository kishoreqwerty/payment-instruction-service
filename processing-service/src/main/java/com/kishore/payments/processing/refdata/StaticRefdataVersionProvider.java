package com.kishore.payments.processing.refdata;

import org.springframework.stereotype.Component;

/** Phase 4 has no refdata admin flow -- the seed migration is the only writer -- so the version never moves. */
@Component
public class StaticRefdataVersionProvider implements RefdataVersionProvider {

    @Override
    public long currentVersion() {
        return 1L;
    }
}
