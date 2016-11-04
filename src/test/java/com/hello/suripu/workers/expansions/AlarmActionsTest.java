package com.hello.suripu.workers.expansions;


import com.google.common.base.Optional;
import com.google.common.collect.Maps;

import com.hello.suripu.api.expansions.ExpansionProtos;
import com.hello.suripu.core.models.AlarmExpansion;
import com.hello.suripu.core.models.ValueRange;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Map;

import is.hello.gaibu.core.models.Expansion;
import is.hello.gaibu.core.models.MultiDensityImage;
import is.hello.gaibu.core.stores.ExpansionStore;
import is.hello.gaibu.homeauto.clients.HueLight;
import is.hello.gaibu.homeauto.clients.NestThermostat;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class AlarmActionsTest {

    private final ExpansionStore expansionStore = mock(ExpansionStore.class);
    private Expansion fakeHueExpansion;
    private Expansion fakeNestExpansion;

    @Before
    public void setUp() {
        String CLIENT_ID = "client_id";
        final MultiDensityImage icon = new MultiDensityImage("icon@1x.png", "icon@2x.png", "icon@3x.png");
        fakeHueExpansion = new Expansion(1L, Expansion.ServiceName.HUE,
            "Hue Light", "Phillips", "Fake Hue Application", icon, CLIENT_ID, "client_secret",
            "http://localhost/",  "auth_uri", "token_uri", "refresh_uri", Expansion.Category.LIGHT,
            DateTime.now(), 2, "completion_uri", Expansion.State.NOT_CONNECTED, ValueRange.createEmpty());

        fakeNestExpansion = new Expansion(2L, Expansion.ServiceName.NEST,
            "Nest Thermostat", "Nest", "Fake Nest Application", icon, CLIENT_ID, "client_secret",
            "http://localhost/",  "auth_uri", "token_uri", "refresh_uri", Expansion.Category.TEMPERATURE,
            DateTime.now(), 2, "completion_uri", Expansion.State.NOT_CONNECTED, ValueRange.createEmpty());

        Mockito.when(expansionStore.getApplicationByName(Expansion.ServiceName.HUE.toString())).thenReturn(Optional.of(fakeHueExpansion));
        Mockito.when(expansionStore.getApplicationByName(Expansion.ServiceName.NEST.toString())).thenReturn(Optional.of(fakeNestExpansion));
    }

    @Test
    public void testShouldAttemptAction() {

        final Integer maxValue = 100;
        final Integer minValue = 100;
        final Long expectedRingTime = 123456000L;
        final Long beforeExpectedRingTime = 123455000L;
        final Long tooFarBeforeExpectedRingTime =  expectedRingTime - (Math.max(HueLight.DEFAULT_BUFFER_TIME_SECONDS, NestThermostat.DEFAULT_BUFFER_TIME_SECONDS) * 1000);
        final Long afterExpectedRingTime = 123457000L;
        final String deviceId = "fake-sense";
        final ValueRange actionValueRange = new ValueRange(minValue, maxValue);
        final AlarmExpansion alarmExpansion = new AlarmExpansion(fakeHueExpansion.id, true, fakeHueExpansion.category.toString(), fakeHueExpansion.serviceName.toString(), actionValueRange);
        final ExpansionAlarmAction alarmAction = new ExpansionAlarmAction(deviceId, alarmExpansion, expectedRingTime);

        final Map<String, Long> recentActionsAlreadyExecuted = Maps.newHashMap();
        recentActionsAlreadyExecuted.put(alarmAction.getExpansionActionKey(), alarmAction.expectedRingTime);

        final Map<String, Long> recentActionsNotAlreadyExecuted = Maps.newHashMap();

        final ExpansionProtos.AlarmAction pb = ExpansionProtos.AlarmAction.newBuilder()
            .setDeviceId(deviceId)
            .setExpectedRingtimeUtc(expectedRingTime)
            .setServiceType(ExpansionProtos.ServiceType.HUE)
            .setTargetValueMax(maxValue)
            .setTargetValueMin(minValue)
            .build();
        final ExpansionProtos.AlarmAction pbNoDevice = ExpansionProtos.AlarmAction.newBuilder()
            .setExpectedRingtimeUtc(expectedRingTime)
            .setServiceType(ExpansionProtos.ServiceType.HUE)
            .setTargetValueMax(maxValue)
            .setTargetValueMin(minValue)
            .build();
        final ExpansionProtos.AlarmAction pbNoService = ExpansionProtos.AlarmAction.newBuilder()
            .setDeviceId(deviceId)
            .setExpectedRingtimeUtc(expectedRingTime)
            .setTargetValueMax(maxValue)
            .setTargetValueMin(minValue)
            .build();
        final ExpansionProtos.AlarmAction pbNoRingTime = ExpansionProtos.AlarmAction.newBuilder()
            .setDeviceId(deviceId)
            .setServiceType(ExpansionProtos.ServiceType.HUE)
            .setTargetValueMax(maxValue)
            .setTargetValueMin(minValue)
            .build();

        //Normal execution
        assertEquals(true, AlarmActionRecordProcessor.shouldAttemptAction(pb, expansionStore, recentActionsNotAlreadyExecuted, expectedRingTime));
        //Normal execution before ring time
        assertEquals(true, AlarmActionRecordProcessor.shouldAttemptAction(pb, expansionStore, recentActionsNotAlreadyExecuted, beforeExpectedRingTime));
        //Normal execution after ring time
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pb, expansionStore, recentActionsNotAlreadyExecuted, afterExpectedRingTime));
        //Normal execution too far before ring time
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pb, expansionStore, recentActionsNotAlreadyExecuted, tooFarBeforeExpectedRingTime));

        //Already executed
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pb, expansionStore, recentActionsAlreadyExecuted, expectedRingTime));

        //Protobuf with no Device ID
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pbNoDevice, expansionStore, recentActionsNotAlreadyExecuted, expectedRingTime));
        //Protobuf with no ServiceType
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pbNoService, expansionStore, recentActionsNotAlreadyExecuted, expectedRingTime));
        //Protobuf with no RingTime
        assertEquals(false, AlarmActionRecordProcessor.shouldAttemptAction(pbNoRingTime, expansionStore, recentActionsNotAlreadyExecuted, expectedRingTime));


    }
}
