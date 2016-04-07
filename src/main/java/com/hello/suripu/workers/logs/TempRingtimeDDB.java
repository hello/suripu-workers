package com.hello.suripu.workers.logs;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.models.RingTime;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TempRingtimeDDB implements RingTimeHistoryReadDAO {

    private final static Logger LOGGER = LoggerFactory.getLogger(TempRingtimeDDB.class);

    public static final String MORPHEUS_ID_ATTRIBUTE_NAME = "device_id";
    public static final String ACCOUNT_ID_ATTRIBUTE_NAME = "account_id";
    public static final String ACTUAL_RING_TIME_ATTRIBUTE_NAME = "actual_ring_time";
    public static final String EXPECTED_RING_TIME_ATTRIBUTE_NAME = "expected_ring_time";
    public static final String RINGTIME_OBJECT_ATTRIBUTE_NAME = "ring_time_object";
    public static final String CREATED_AT_ATTRIBUTE_NAME = "created_at_utc";

    private final AmazonDynamoDB amazonDynamoDB;
    private final String tableName;
    private final ObjectMapper mapper;

    public TempRingtimeDDB(final AmazonDynamoDB amazonDynamoDB, final String tableName) {
        this.amazonDynamoDB = amazonDynamoDB;
        this.tableName = tableName;
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<RingTime> getRingTimesBetween(final String senseId, final Long accountId,
                                              final DateTime startTime,
                                              final DateTime endTime) {
        final Map<String, Condition> queryConditions = Maps.newHashMap();
        final List<AttributeValue> values = Lists.newArrayList();
        values.add(new AttributeValue().withN(String.valueOf(startTime.getMillis())));
        values.add(new AttributeValue().withN(String.valueOf(endTime.getMillis())));

        final Condition selectDateCondition = new Condition()
                .withComparisonOperator(ComparisonOperator.BETWEEN.toString())
                .withAttributeValueList(values);
        queryConditions.put(EXPECTED_RING_TIME_ATTRIBUTE_NAME, selectDateCondition);

        final Condition selectAccountIdCondition = new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(new AttributeValue().withN(accountId.toString()));

        final Condition selectSenseIdCondition = new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(new AttributeValue().withS(senseId));

        queryConditions.put(ACCOUNT_ID_ATTRIBUTE_NAME, selectAccountIdCondition);
        //queryConditions.put(MORPHEUS_ID_ATTRIBUTE_NAME, selectSenseIdCondition);
        final Map<String, Condition> filterConditions = Maps.newHashMap();
        filterConditions.put(MORPHEUS_ID_ATTRIBUTE_NAME, selectSenseIdCondition);

        final Set<String> targetAttributeSet = Sets.newHashSet(ACCOUNT_ID_ATTRIBUTE_NAME,
                MORPHEUS_ID_ATTRIBUTE_NAME,
                EXPECTED_RING_TIME_ATTRIBUTE_NAME,
                ACTUAL_RING_TIME_ATTRIBUTE_NAME,
                RINGTIME_OBJECT_ATTRIBUTE_NAME,
                CREATED_AT_ATTRIBUTE_NAME);
        final List<RingTime> ringTimes = Lists.newArrayList();
        Map<String, AttributeValue> lastEvaluatedKey = null;
        int maxAttempts = 1;
        int attempts = 0;
        do {
            final QueryRequest queryRequest = new QueryRequest(tableName).withKeyConditions(queryConditions)
                    .withQueryFilter(filterConditions)
                    .withAttributesToGet(targetAttributeSet)
                    .withLimit(50)
                    .withExclusiveStartKey(lastEvaluatedKey)
                    .withScanIndexForward(false);
            final QueryResult queryResult = this.amazonDynamoDB.query(queryRequest);
            lastEvaluatedKey = queryResult.getLastEvaluatedKey();

            if (queryResult.getItems() == null) {
                return Collections.EMPTY_LIST;
            }

            final List<Map<String, AttributeValue>> items = queryResult.getItems();


            for (final Map<String, AttributeValue> item : items) {
                final Optional<RingTime> ringTime = Optional.fromNullable(ringTimeFromItemSet(senseId, targetAttributeSet, item));
                if (!ringTime.isPresent()) {
                    continue;
                }

                ringTimes.add(ringTime.get());
            }
            attempts++;
        }while (lastEvaluatedKey != null & attempts <= maxAttempts);

        Collections.sort(ringTimes, new Comparator<RingTime>() {
            @Override
            public int compare(final RingTime o1, final RingTime o2) {
                return Long.compare(o1.actualRingTimeUTC, o2.actualRingTimeUTC);
            }
        });

        return ringTimes;
    }

    public RingTime ringTimeFromItemSet(final String deviceId, final Collection<String> targetAttributeSet, final Map<String, AttributeValue> item){

        if(!item.keySet().containsAll(targetAttributeSet)){
            LOGGER.warn("Missing field in item {}", item);
            return null;
        }

        try {
            final String ringTimeJSONString = item.get(RINGTIME_OBJECT_ATTRIBUTE_NAME).getS();
            final RingTime ringTime = this.mapper.readValue(ringTimeJSONString, RingTime.class);

            return ringTime;
        }catch (Exception ex){
            LOGGER.error("Get ring time failed for device {}.", deviceId);
        }

        return null;

    }
}
