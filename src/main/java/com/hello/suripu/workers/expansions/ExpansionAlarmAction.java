package com.hello.suripu.workers.expansions;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.core.models.AlarmExpansion;

/**
 * Created by jnorgan on 11/1/16.
 */
public class ExpansionAlarmAction {
  @JsonProperty("sense_id")
  public final String senseId;

  @JsonProperty("alarm_expansion")
  public final AlarmExpansion alarmExpansion;

  @JsonProperty("expected_ring_time")
  public final Long expectedRingTime;

  public ExpansionAlarmAction (final String senseId, final AlarmExpansion alarmExpansion, final Long expectedRingTime) {
    this.senseId = senseId;
    this.alarmExpansion = alarmExpansion;
    this.expectedRingTime = expectedRingTime;
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return super.equals(obj);
  }

  public String getExpansionActionKey() {
    return senseId + "|" + alarmExpansion.serviceName + "|" + expectedRingTime;
  }
}
