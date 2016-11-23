package com.hello.suripu.workers.expansions;

import com.google.common.base.Optional;
import com.hello.suripu.core.alerts.Alert;
import com.hello.suripu.core.alerts.AlertsDAO;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.TimeZoneHistoryDAO;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.TimeZoneHistory;
import is.hello.gaibu.homeauto.models.AlarmActionStatus;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AlarmActionAlerter implements Alerter {

    private final static Logger LOGGER = LoggerFactory.getLogger(AlarmActionAlerter.class);

    private final DeviceDAO deviceDAO;
    private final AlertsDAO alertsDAO;
    private final TimeZoneHistoryDAO timeZoneHistoryDAO;

    public AlarmActionAlerter(final DeviceDAO deviceDAO, final AlertsDAO alertsDAO, final TimeZoneHistoryDAO timeZoneHistoryDAO) {
        this.deviceDAO = deviceDAO;
        this.alertsDAO = alertsDAO;
        this.timeZoneHistoryDAO = timeZoneHistoryDAO;
    }

    Optional<Alert> createAlert(final Long accountId, final AlarmActionStatus status, final DateTime referenceTime) {
        switch (status) {
            case OFF_OR_LOCKED:
                final String maybeTime = generateTimeString(accountId, referenceTime);
                final Alert alert = Alert.unreachable(
                        1L,
                        accountId,
                        "Thermostat not available",
                        String.format("Sense was unable to adjust the temperature%s. Ensure a thermostat is selected, on, and unlocked.",maybeTime),
                        referenceTime);
                return Optional.of(alert);
        }
        LOGGER.warn("warn=no-alert-for-status status={} account_id={}", status, accountId);
        return Optional.absent();
    }


    String generateTimeString(final Long accountId, final DateTime referenceTime) {
        final Optional<TimeZoneHistory> timeZoneHistoryOptional = timeZoneHistoryDAO.getCurrentTimeZone(accountId);
        if(!timeZoneHistoryOptional.isPresent()) {
            return "";
        }

        final DateTime localTime = new DateTime(referenceTime.getMillis(), DateTimeZone.forID(timeZoneHistoryOptional.get().timeZoneId));
        return String.format(" at %s", localTime.toString(DateTimeFormat.forPattern("HH:mma")));
    }

    @Override
    public void alert(final String senseId, final AlarmActionStatus status) {
        final List<DeviceAccountPair> pairs = deviceDAO.getAccountIdsForDeviceId(senseId);
        for(final DeviceAccountPair pair : pairs) {

            final Optional<Alert> alert = createAlert(pair.accountId, status, DateTime.now(DateTimeZone.UTC));
            if(alert.isPresent()) {
                final Long alertId = alertsDAO.insert(alert.get());
                LOGGER.info("action=alert-insert id={} account_id={}", alertId, pair.accountId);
            } else {
                LOGGER.warn("warn=alert-insert-skipped account_id={}", pair.accountId);
            }
        }
    }
}
