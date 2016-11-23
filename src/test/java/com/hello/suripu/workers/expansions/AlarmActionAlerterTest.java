package com.hello.suripu.workers.expansions;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.hello.suripu.core.alerts.Alert;
import com.hello.suripu.core.alerts.AlertCategory;
import com.hello.suripu.core.alerts.AlertsDAO;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.TimeZoneHistoryDAO;
import com.hello.suripu.core.models.TimeZoneHistory;
import is.hello.gaibu.homeauto.models.AlarmActionStatus;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AlarmActionAlerterTest {

    private final DeviceDAO deviceDAO = mock(DeviceDAO.class);
    private final AlertsDAO alertsDAO = mock(AlertsDAO.class);
    private final TimeZoneHistoryDAO timeZoneHistoryDAO = mock(TimeZoneHistoryDAO.class);
    private final AlarmActionAlerter alerter = new AlarmActionAlerter(deviceDAO, alertsDAO, timeZoneHistoryDAO);

    @Test
    public void testFormat() {

        final Long accountId = 1L;

        final Map<String, String> map = Maps.newHashMap();
        map.put("America/Los_Angeles", " at 4:02 PM");
        map.put("Europe/London", " at 12:02 AM");
        map.put("Europe/Berlin", " at 1:02 AM");
        map.put(null, ""); // missing timezone history

        for(final String key : map.keySet()) {

            final TimeZoneHistory tzHistory = key == null ? null : new TimeZoneHistory(0, key);
            when(timeZoneHistoryDAO.getCurrentTimeZone(accountId)).thenReturn(Optional.fromNullable(tzHistory));
            final String timeString = alerter.generateTimeString(accountId, new DateTime(2017, 1, 1, 0, 2, 0, DateTimeZone.UTC));
            assertThat(timeString, equalTo(map.get(key)));
        }
    }

    @Test
    public void generateAlert() {
        final DateTime now = new DateTime(2000,2,10, 0,59,0, DateTimeZone.UTC);
        final Optional<Alert> alert = alerter.createAlert(1L, AlarmActionStatus.OK, now);
        assertFalse("alert is present", alert.isPresent());

        when(timeZoneHistoryDAO.getCurrentTimeZone(1L)).thenReturn(Optional.of(new TimeZoneHistory(0, "America/Los_Angeles")));
        final Optional<Alert> alert2 = alerter.createAlert(1L, AlarmActionStatus.OFF_OR_LOCKED, now);
        assertTrue("alert is present", alert2.isPresent());
        assertEquals(alert2.get().category(), AlertCategory.EXPANSION_UNREACHABLE);
        assertTrue("contains :59", alert2.get().body().contains(":59"));
    }
}
