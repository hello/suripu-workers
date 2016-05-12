package com.hello.suripu.workers.pill.notifications;

import com.codahale.metrics.Meter;
import com.google.common.base.Optional;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.db.responses.Response;
import com.hello.suripu.core.models.Alarm;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import com.hello.suripu.core.notifications.MobilePushNotificationProcessor;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import com.hello.suripu.core.notifications.PushNotificationEventDynamoDB;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Created by jakepiccolo on 5/12/16.
 */
public class PillBatteryNotificationProcessorTest {

    private PillBatteryNotificationConfig pillBatteryNotificationConfig;
    private MobilePushNotificationProcessor mobilePushNotificationProcessor;
    private PillBatteryNotificationProcessor pillBatteryNotificationProcessor;
    private List<PushNotificationEvent> sentEventList;

    @Before
    public void setUp() throws Exception {
        pillBatteryNotificationConfig = Mockito.mock(PillBatteryNotificationConfig.class);
        mobilePushNotificationProcessor = Mockito.mock(MobilePushNotificationProcessor.class);

        // Whenever a notification is pushed, add it to our list
        sentEventList = new ArrayList<>();
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                final PushNotificationEvent event = (PushNotificationEvent) invocation.getArguments()[0];
                sentEventList.add(event);
                return null;
            }
        }).when(mobilePushNotificationProcessor).push(Mockito.any(PushNotificationEvent.class));

        final Meter meter = Mockito.mock(Meter.class);
        Mockito.doNothing().when(meter).mark();
        pillBatteryNotificationProcessor = new PillBatteryNotificationProcessor(pillBatteryNotificationConfig, mobilePushNotificationProcessor, meter);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSendLowBatteryNotificationIncorrectTimeZone() throws Exception {
        final DateTime dateTime = new DateTime(DateTimeZone.forOffsetHours(-8));
        pillBatteryNotificationProcessor.sendLowBatteryNotification("", UserInfo.createEmpty("", 1L), 0, dateTime);
    }

    @Test
    public void testSendLowBatteryNotificationHighPillBattery() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 10;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 12, 0, DateTimeZone.UTC);
        Mockito.when(pillBatteryNotificationConfig.getBatteryNotificationPercentageThreshold()).thenReturn(batteryLevel);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, jakeCakeDay);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationNoTimeZone() throws Exception {
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 12, 0, DateTimeZone.UTC);
        Mockito.when(pillBatteryNotificationConfig.getBatteryNotificationPercentageThreshold()).thenReturn(10);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", UserInfo.createEmpty("", 1L), batteryLevel, jakeCakeDay);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationTooEarlyInDay() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 8, 0, userInfo.timeZone.get());
        final DateTime now = new DateTime(jakeCakeDay, DateTimeZone.UTC);
        Mockito.when(pillBatteryNotificationConfig.getBatteryNotificationPercentageThreshold()).thenReturn(10);
        Mockito.when(pillBatteryNotificationConfig.getMinHourOfDay()).thenReturn(10);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, now);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationTooLateInDay() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 22, 0, userInfo.timeZone.get());
        final DateTime now = new DateTime(jakeCakeDay, DateTimeZone.UTC);
        setBatteryThreshold(10);
        setMinMaxHours(10, 20);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, now);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationCannotGetPreviouslySent() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 16, 0, userInfo.timeZone.get());
        final DateTime now = new DateTime(jakeCakeDay, DateTimeZone.UTC);
        setBatteryThreshold(10);
        setMinMaxHours(10, 20);
        setDaysBetweenNotifications(7);
        setPushNotificationEventDynamoDBResponse(Response.Status.FAILURE);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, now);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationSentRecently() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 16, 0, userInfo.timeZone.get());
        final DateTime now = new DateTime(jakeCakeDay, DateTimeZone.UTC);
        setBatteryThreshold(10);
        setMinMaxHours(10, 20);
        setDaysBetweenNotifications(7);
        setPushNotificationEventDynamoDBResponse(1);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, now);
        assertThat(didSend, is(false));
        assertThat(sentEventList.isEmpty(), is(true));
    }

    @Test
    public void testSendLowBatteryNotificationHappyPath() throws Exception {
        final UserInfo userInfo = makeUserInfo();
        final int batteryLevel = 8;
        final DateTime jakeCakeDay = new DateTime(2016, 10, 24, 16, 0, userInfo.timeZone.get());
        final DateTime now = new DateTime(jakeCakeDay, DateTimeZone.UTC);
        setBatteryThreshold(10);
        setMinMaxHours(10, 20);
        setDaysBetweenNotifications(7);
        setPushNotificationEventDynamoDBResponse(Response.Status.SUCCESS);
        final boolean didSend = pillBatteryNotificationProcessor.sendLowBatteryNotification("", userInfo, batteryLevel, now);
        assertThat(didSend, is(true));
        assertThat(sentEventList.size(), is(1));
        assertThat(sentEventList.get(0).accountId, is(userInfo.accountId));
        assertThat(sentEventList.get(0).timestamp, is(now));
    }


    //region private helpers
    private UserInfo makeUserInfo(final String senseId, final Long accountId, final DateTimeZone timeZone) {
        return new UserInfo(senseId, accountId, new ArrayList<Alarm>(), Optional.<RingTime>absent(), Optional.of(timeZone),
                Optional.<OutputProtos.SyncResponse.PillSettings>absent(), 0L);
    }

    private UserInfo makeUserInfo() {
        return makeUserInfo("senseId", 1L, DateTimeZone.forOffsetHours(-8));
    }

    private void setBatteryThreshold(final int threshold) {
        Mockito.when(pillBatteryNotificationConfig.getBatteryNotificationPercentageThreshold()).thenReturn(threshold);
    }

    private void setMinMaxHours(final int min, final int max) {
        Mockito.when(pillBatteryNotificationConfig.getMinHourOfDay()).thenReturn(min);
        Mockito.when(pillBatteryNotificationConfig.getMaxHourOfDay()).thenReturn(max);
    }

    private void setDaysBetweenNotifications(final int days) {
        Mockito.when(pillBatteryNotificationConfig.getDaysBetweenNotifications()).thenReturn(days);
    }

    private void setPushNotificationEventDynamoDBResponse(final Response.Status status) {
        final List<PushNotificationEvent> data = new ArrayList<>();
        final Response<List<PushNotificationEvent>> response = Response.create(data, status);
        setPushNotificationEventDynamoDBResponse(response);
    }

    private void setPushNotificationEventDynamoDBResponse(final int numResults) {
        final List<PushNotificationEvent> data = new ArrayList<>();
        for (int i = 0; i < numResults; i++) {
            data.add(Mockito.mock(PushNotificationEvent.class));
        }
        final Response<List<PushNotificationEvent>> response = Response.success(data);
        setPushNotificationEventDynamoDBResponse(response);
    }

    private void setPushNotificationEventDynamoDBResponse(final Response<List<PushNotificationEvent>> response) {
        final PushNotificationEventDynamoDB pushNotificationEventDynamoDB = Mockito.mock(PushNotificationEventDynamoDB.class);
        Mockito.when(pushNotificationEventDynamoDB
                .query(Mockito.anyLong(), Mockito.any(DateTime.class), Mockito.any(DateTime.class), Mockito.anyString()))
                .thenReturn(response);
        Mockito.when(mobilePushNotificationProcessor.getPushNotificationEventDynamoDB()).thenReturn(pushNotificationEventDynamoDB);
    }
    //endregion
}