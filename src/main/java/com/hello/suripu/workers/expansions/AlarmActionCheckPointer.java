package com.hello.suripu.workers.expansions;

import java.util.Map;
import java.util.Set;

/**
 * Created by jnorgan on 11/3/16.
 */
public interface AlarmActionCheckPointer {

  Map<String, Long> getAllRecentActions(final Long oldestEventMillis);

  Boolean recordAlarmActions(final Set<ExpansionAlarmAction> executedActions);

  Long removeOldActions(final Long oldestEventMillis);
}
