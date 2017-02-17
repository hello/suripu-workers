package com.hello.suripu.workers.notifications;

import com.google.common.base.Optional;
import com.hello.suripu.api.notifications.PushNotification;
import com.hello.suripu.core.notifications.HelloPushMessage;

public class HelloPushMessageGenerator {

    public Optional<HelloPushMessage> generate(final PushNotification.UserPushNotification userPushNotification) {
        if(userPushNotification.hasNewSleepScore()) {
            return generateSleepScoreMessage(userPushNotification);
        }

        if(userPushNotification.hasPillBatteryLow()) {
            return generateLowBatteryMessage(userPushNotification);
        }

        return Optional.absent();
    }

    static Optional<HelloPushMessage> generateSleepScoreMessage(final PushNotification.UserPushNotification userPushNotification) {
        final String nightOf = userPushNotification.getNewSleepScore().getDate().substring(0,10);
        final HelloPushMessage msg = new HelloPushMessage(String.format("Your Sleep Score for last night is %d.", userPushNotification.getNewSleepScore().getScore()), "sleep_score", nightOf );
        return Optional.of(msg);
    }

    static Optional<HelloPushMessage> generateLowBatteryMessage(final PushNotification.UserPushNotification userPushNotification) {
        final HelloPushMessage pushMessage = new HelloPushMessage("Your Sleep Pill battery is low.", "system", "pill_battery");
        return Optional.of(pushMessage);
    }
}
