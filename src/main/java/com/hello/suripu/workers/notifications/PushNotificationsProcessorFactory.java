package com.hello.suripu.workers.notifications;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;

import java.util.Set;

public class PushNotificationsProcessorFactory implements IRecordProcessorFactory {

    private final MobilePushNotificationProcessor mobilePushNotificationProcessor;
    private final HelloPushMessageGenerator pushMessageGenerator;
    private final Set<Integer> activeHours;

    public PushNotificationsProcessorFactory(final MobilePushNotificationProcessor mobilePushNotificationProcessor,
                                             final HelloPushMessageGenerator pushMessageGenerator,
                                             final Set<Integer> activeHours) {
        this.mobilePushNotificationProcessor = mobilePushNotificationProcessor;
        this.pushMessageGenerator = pushMessageGenerator;
        this.activeHours = activeHours;
    }

    @Override
    public IRecordProcessor createProcessor()  {

        return new PushNotificationsProcessor(mobilePushNotificationProcessor, pushMessageGenerator, activeHours);
    }
}
