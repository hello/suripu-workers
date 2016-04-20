package com.hello.suripu.workers.logs;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.models.Alarm;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.UserInfo;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class NonRateLimitedRingtimeDDBIT {

    private final static Logger LOGGER = LoggerFactory.getLogger(NonRateLimitedRingtimeDDBIT.class);

    private BasicAWSCredentials awsCredentials;
    private AmazonDynamoDBClient amazonDynamoDBClient;
    private RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB;
    private RingTimeHistoryReadDAO nonRateLimitedRingtimeDDB;
    private final String tableName = "ring_history_by_account_test";
    private final static String SENSE_ID = "TEST_SENSE";

    @Before
    public void setUp(){
        this.awsCredentials = new BasicAWSCredentials("FAKE_AWS_KEY", "FAKE_AWS_SECRET");
        final ClientConfiguration clientConfiguration = new ClientConfiguration().withMaxErrorRetry(0);
        this.amazonDynamoDBClient = new AmazonDynamoDBClient(this.awsCredentials, clientConfiguration);
        this.amazonDynamoDBClient.setEndpoint("http://localhost:7777");

        cleanUp();

        try {
            RingTimeHistoryDAODynamoDB.createTable(tableName, amazonDynamoDBClient);
            ringTimeHistoryDAODynamoDB = new RingTimeHistoryDAODynamoDB(amazonDynamoDBClient, tableName);
            nonRateLimitedRingtimeDDB = new NonRateLimitedRingtimeDDB(amazonDynamoDBClient, tableName);
        }catch (ResourceInUseException rie){
            LOGGER.warn("Table already exists");
        }
    }

    @After
    public void cleanUp(){
        final DeleteTableRequest deleteTableRequest = new DeleteTableRequest()
                .withTableName(tableName);
        try {
            amazonDynamoDBClient.deleteTable(deleteTableRequest);
        }catch (ResourceNotFoundException ex){
            LOGGER.warn("Can't delete existing table");
        }
    }

    @Test
    public void testNonSmartAlarm() {
        final DateTime expected = DateTime.now(DateTimeZone.UTC);
        final DateTime actual = expected.minusMinutes(0);
        final Long accountId = 999L;

        final RingTime ringTime = new RingTime(actual.getMillis(), expected.getMillis(), 1, false);
        final List<UserInfo> userInfoList = createUserInfos(SENSE_ID, accountId, ringTime);


        ringTimeHistoryDAODynamoDB.setNextRingTime(SENSE_ID, userInfoList, ringTime);
        final DateTime startTime = actual.minusMinutes(5);
        final DateTime endTime = actual.plusMinutes(5);
        final List<RingTime> ringTimes = nonRateLimitedRingtimeDDB.getRingTimesBetween(
                SENSE_ID,
                accountId,
                startTime,
                endTime
        );

        assertThat(ringTimes.isEmpty(), is(false));
    }


    @Test
    public void testSmartAlarm() {
        final DateTime expected = DateTime.now(DateTimeZone.UTC);
        final DateTime actual = expected.minusMinutes(35);
        final Long accountId = 999L;

        final RingTime ringTime = new RingTime(actual.getMillis(), expected.getMillis(), 1, true);
        final List<UserInfo> userInfoList = createUserInfos(SENSE_ID, accountId, ringTime);


        ringTimeHistoryDAODynamoDB.setNextRingTime(SENSE_ID, userInfoList, ringTime);
        final DateTime eventReceivedAt = actual;
        final DateTime startTime = eventReceivedAt.minusMinutes(5);
        final DateTime endTime = eventReceivedAt.plusMinutes(5);
        final List<RingTime> ringTimes = nonRateLimitedRingtimeDDB.getRingTimesBetween(
                SENSE_ID,
                accountId,
                startTime,
                endTime
        );

        assertThat(ringTimes.isEmpty(), is(false));
    }


    @Test
    public void testWrongSense() {
        final DateTime expected = DateTime.now(DateTimeZone.UTC);
        final DateTime actual = expected.minusMinutes(35);
        final Long accountId = 999L;

        final RingTime ringTime = new RingTime(actual.getMillis(), expected.getMillis(), 1, true);
        final List<UserInfo> userInfoList = createUserInfos(SENSE_ID, accountId, ringTime);


        ringTimeHistoryDAODynamoDB.setNextRingTime("WRONG_SENSE_ID", userInfoList, ringTime);
        final DateTime eventReceivedAt = actual;
        final DateTime startTime = eventReceivedAt.minusMinutes(5);
        final DateTime endTime = eventReceivedAt.plusMinutes(5);
        final List<RingTime> ringTimes = nonRateLimitedRingtimeDDB.getRingTimesBetween(
                SENSE_ID,
                accountId,
                startTime,
                endTime
        );

        assertThat(ringTimes.isEmpty(), is(true));
    }

    @Test
    public void testCorrectSenseWrongAccountId() {
        final DateTime expected = DateTime.now(DateTimeZone.UTC);
        final DateTime actual = expected.minusMinutes(35);
        final Long accountId = 999L;
        final Long wrongAccountId = 888L;
        final RingTime ringTime = new RingTime(actual.getMillis(), expected.getMillis(), 1, true);
        final List<UserInfo> userInfoList = createUserInfos(SENSE_ID, accountId, ringTime);

        ringTimeHistoryDAODynamoDB.setNextRingTime(SENSE_ID, userInfoList, ringTime);
        final DateTime eventReceivedAt = actual;
        final DateTime startTime = eventReceivedAt.minusMinutes(5);
        final DateTime endTime = eventReceivedAt.plusMinutes(5);
        final List<RingTime> ringTimes = nonRateLimitedRingtimeDDB.getRingTimesBetween(
                SENSE_ID,
                wrongAccountId,
                startTime,
                endTime
        );

        assertThat(ringTimes.isEmpty(), is(true));
    }

    @Test
    public void testEventReceivedOutsideWindow() {
        final DateTime expected = DateTime.now(DateTimeZone.UTC);
        final DateTime actual = expected.minusMinutes(35);
        final Long accountId = 999L;
        final Long wrongAccountId = 888L;
        final RingTime ringTime = new RingTime(actual.getMillis(), expected.getMillis(), 1, true);
        final List<UserInfo> userInfoList = createUserInfos(SENSE_ID, accountId, ringTime);


        ringTimeHistoryDAODynamoDB.setNextRingTime(SENSE_ID, userInfoList, ringTime);
        final DateTime eventReceivedAt = DateTime.now(DateTimeZone.UTC).plusMinutes(6);
        final DateTime startTime = eventReceivedAt.minusMinutes(5);
        final DateTime endTime = eventReceivedAt.plusMinutes(5);
        final List<RingTime> ringTimes = nonRateLimitedRingtimeDDB.getRingTimesBetween(
                SENSE_ID,
                accountId,
                startTime,
                endTime
        );

        assertThat(ringTimes.isEmpty(), is(true));
    }

    private List<UserInfo> createUserInfos(final String senseId, final Long accountId, final RingTime ringTime) {
        final List<UserInfo> userInfoList = Lists.newArrayList(
                new UserInfo(senseId, accountId, Lists.<Alarm>newArrayList(), Optional.of(ringTime),
                        Optional.<DateTimeZone>absent(), Optional.<OutputProtos.SyncResponse.PillSettings>absent(),
                        DateTime.now(DateTimeZone.UTC).getMillis())
        );
        return userInfoList;
    }
}
