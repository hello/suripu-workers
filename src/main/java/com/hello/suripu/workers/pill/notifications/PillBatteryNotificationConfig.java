package com.hello.suripu.workers.pill.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.workers.framework.WorkerConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * Created by jakepiccolo on 5/12/16.
 */
public class PillBatteryNotificationConfig extends WorkerConfiguration {
    @Valid
    @JsonProperty("days_between_notifications")
    private Integer daysBetweenNotifications = 7;
    public Integer getDaysBetweenNotifications() { return daysBetweenNotifications; }

    @Valid
    @JsonProperty("battery_notification_percentage_threshold")
    private Integer batteryNotificationPercentageThreshold = 10;
    public Integer getBatteryNotificationPercentageThreshold() { return batteryNotificationPercentageThreshold; }

    @Valid
    @JsonProperty("min_hour_of_day")
    @Min(0)
    @Max(24)
    private Integer minHourOfDay = 11;
    public Integer getMinHourOfDay() { return minHourOfDay; }

    @Valid
    @JsonProperty("max_hour_of_day")
    @Min(0)
    @Max(24)
    private Integer maxHourOfDay = 20;
    public Integer getMaxHourOfDay() { return maxHourOfDay; }
}
