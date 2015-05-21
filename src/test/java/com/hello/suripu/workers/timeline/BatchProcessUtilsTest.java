package com.hello.suripu.workers.timeline;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

/**
 * Created by pangwu on 1/29/15.
 */
public class BatchProcessUtilsTest {


    private DeviceDAO deviceDAO = mock(DeviceDAO.class);
    private MergedUserInfoDynamoDB mergedUserInfoDynamoDB = mock(MergedUserInfoDynamoDB.class);

    private ImmutableList<DeviceAccountPair> getDevice(final long accountId, final long internalDeviceId, final String externalDeviceId){
        final List<DeviceAccountPair> deviceAccountPairs = new ArrayList<>();
        deviceAccountPairs.add(new DeviceAccountPair(accountId, internalDeviceId, externalDeviceId, DateTime.now()));
        return ImmutableList.copyOf(deviceAccountPairs);
    }

    @Test
    public void testGroupAccountAndExpireDateLocalUTCWithinProcessInterval(){
        final Map<String, Set<DateTime>> groupedPillIds = new HashMap<>();
        final DateTime dataDate1 = new DateTime(2015, 1, 21, 4, 10, DateTimeZone.UTC); // 1/20/2015 23:00 PST
        final DateTime dataDate2 = new DateTime(2015, 1, 20, 8, 0, DateTimeZone.UTC);  // 1/20/2015 00:00 PST
        final Set<DateTime> dataDatesUTC = new HashSet<>();
        dataDatesUTC.add(dataDate1);
        dataDatesUTC.add(dataDate2);

        final String pillId1 = "Pang's 911";
        final String sensId = "Sense";
        groupedPillIds.put(pillId1, dataDatesUTC);

        final long accountId = 1L;

        final Map<Long, UserInfo> accountIdUserInfoMap = getAccountIdUserInfoMap(sensId, accountId, DateTimeZone.forID("America/Los_Angeles"));
        final Map<String, List<DeviceAccountPair>> pillIdLinkedAccountPairs = getPillIdLinkedAccountsMap(pillId1, 2L, accountId);

        final Map<Long, Set<DateTime>> groupedtargetDateLocalUTC = BatchProcessUtils.groupAccountAndExpireDateLocalUTC(groupedPillIds,
                accountIdUserInfoMap,
                pillIdLinkedAccountPairs);

        assertThat(groupedtargetDateLocalUTC.containsKey(accountId), is(true));
        assertThat(groupedtargetDateLocalUTC.get(accountId).contains(new DateTime(2015, 1, 19, 0, 0, DateTimeZone.UTC)), is(true));
        assertThat(groupedtargetDateLocalUTC.get(accountId).contains(new DateTime(2015, 1, 20, 0, 0, DateTimeZone.UTC)), is(true));
    }

    private Map<String, List<DeviceAccountPair>> getPillIdLinkedAccountsMap(final String pillId, final long internalPillId, final Long accountId){
        final Map<String, List<DeviceAccountPair>> pillIdLinkedAccountPairs = new HashMap<>();
        pillIdLinkedAccountPairs.put(pillId, getDevice(accountId, internalPillId, pillId));
        return pillIdLinkedAccountPairs;
    }

    private Map<Long, UserInfo> getAccountIdUserInfoMap(final String senseId, final Long accountId, final DateTimeZone timeZone){
        final UserInfo userInfo = new UserInfo(senseId, accountId, Collections.EMPTY_LIST, Optional.<RingTime>absent(),
                Optional.fromNullable(timeZone), Optional.<OutputProtos.SyncResponse.PillSettings>absent(),
                0);
        final Map<Long, UserInfo> accountIdUserInfoMap = new HashMap<>();
        accountIdUserInfoMap.put(accountId, userInfo);
        return accountIdUserInfoMap;
    }


    @Test
    public void testGroupAccountAndProcessDateLocalUTCTooLateToProcess(){
        final HashMap<String, Set<DateTime>> groupedPillIds = new HashMap<>();
        final DateTime targetDate2 = new DateTime(2015, 1, 20, 20, 0, DateTimeZone.UTC);
        final Set<DateTime> targetDatesUTC = new HashSet<>();
        targetDatesUTC.add(targetDate2);
        final String pillId1 = "Pang's 911";
        final String sensId = "Sense";
        groupedPillIds.put(pillId1, targetDatesUTC);
        final long accountId = 1L;
        final Map<Long, UserInfo> accountIdUserInfoMap = getAccountIdUserInfoMap(sensId, accountId, DateTimeZone.UTC);
        final Map<String, List<DeviceAccountPair>> pillIdLinkedAccountPairs = getPillIdLinkedAccountsMap(pillId1, 2L, accountId);

        final Map<Long, Set<DateTime>> groupedtargetDateLocalUTC = BatchProcessUtils.groupAccountAndProcessDateLocalUTC(groupedPillIds,
                5,
                11,
                new DateTime(2015, 1, 20, 20, 1, DateTimeZone.UTC),
                accountIdUserInfoMap,
                pillIdLinkedAccountPairs);
        assertThat(groupedtargetDateLocalUTC.containsKey(accountId), is(false));
    }

    @Test
    public void testGroupAccountAndProcessDateLocalUTCWithinProcessInterval(){
        final Map<String, Set<DateTime>> groupedPillIds = new HashMap<>();
        final DateTime targetDate2 = new DateTime(2015, 1, 20, 8, 0, DateTimeZone.UTC);
        final Set<DateTime> targetDatesUTC = new HashSet<>();
        targetDatesUTC.add(targetDate2);
        final String pillId1 = "Pang's 911";
        final String sensId = "Sense";
        groupedPillIds.put(pillId1, targetDatesUTC);
        final long accountId = 1L;

        final Map<Long, UserInfo> accountIdUserInfoMap = getAccountIdUserInfoMap(sensId, accountId, DateTimeZone.UTC);
        final Map<String, List<DeviceAccountPair>> pillIdLinkedAccountPairs = getPillIdLinkedAccountsMap(pillId1, 2L, accountId);

        final Map<Long, Set<DateTime>> groupedtargetDateLocalUTC = BatchProcessUtils.groupAccountAndProcessDateLocalUTC(groupedPillIds,
                5,
                11,
                new DateTime(2015, 1, 20, 10, 1, DateTimeZone.UTC),
                accountIdUserInfoMap,
                pillIdLinkedAccountPairs);
        assertThat(groupedtargetDateLocalUTC.containsKey(accountId), is(true));
        assertThat(groupedtargetDateLocalUTC.get(accountId).contains(new DateTime(2015, 1, 19, 0, 0, DateTimeZone.UTC)), is(true));
    }


}
