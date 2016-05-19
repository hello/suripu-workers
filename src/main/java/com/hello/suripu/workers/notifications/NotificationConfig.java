package com.hello.suripu.workers.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * Created by jakepiccolo on 5/17/16.
 */
public class NotificationConfig extends Configuration {

    @Valid
    @JsonProperty("days_between_notifications")
    private Integer daysBetweenNotifications = 7;
    public Integer getDaysBetweenNotifications() { return daysBetweenNotifications; }

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
