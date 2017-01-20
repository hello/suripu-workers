package com.hello.suripu.workers.insights;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.AccountReadDAO;
import com.hello.suripu.core.db.AggregateSleepScoreDAODynamoDB;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.db.InsightsDAODynamoDB;
import com.hello.suripu.core.db.MarketingInsightsSeenDAODynamoDB;
import com.hello.suripu.core.db.QuestionResponseReadDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.TrendsInsightsDAO;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.insights.InsightsLastSeenDAO;
import com.hello.suripu.core.preferences.AccountPreferencesDAO;
import com.hello.suripu.core.processors.AccountInfoProcessor;
import com.hello.suripu.core.processors.InsightProcessor;
import com.hello.suripu.core.processors.insights.LightData;
import com.hello.suripu.core.processors.insights.WakeStdDevData;

/**
 * Created by kingshy on 1/6/15.
 */
public class InsightsGeneratorFactory implements IRecordProcessorFactory {
    private final AccountReadDAO accountDAO;
    private final DeviceDataDAODynamoDB deviceDataDAODynamoDB;
    private final DeviceReadDAO deviceDAO;
    private final SenseColorDAO senseColorDAO;
    private final AggregateSleepScoreDAODynamoDB scoreDAODynamoDB;
    private final InsightsDAODynamoDB insightsDAODynamoDB;
    private final InsightsLastSeenDAO insightsLastSeenDAO;
    private final TrendsInsightsDAO trendsInsightsDAO;
    private final QuestionResponseReadDAO questionResponseDAO;
    private final SleepStatsDAODynamoDB sleepStatsDAODynamoDB;
    private final LightData lightData;
    private final WakeStdDevData wakeStdDevData;
    private final AccountPreferencesDAO accountPreferencesDAO;
    private final CalibrationDAO calibrationDAO;
    private final MarketingInsightsSeenDAODynamoDB marketingInsightsSeenDAODynamoDB;

    public InsightsGeneratorFactory(final AccountDAO accountDAO,
                                    final DeviceDataDAODynamoDB deviceDataDAODynamoDB,
                                    final DeviceDAO deviceDAO,
                                    final SenseColorDAO senseColorDAO,
                                    final AggregateSleepScoreDAODynamoDB scoreDAODynamoDB,
                                    final InsightsDAODynamoDB insightsDAODynamoDB,
                                    final InsightsLastSeenDAO insightsLastSeenDAO,
                                    final TrendsInsightsDAO trendsInsightsDAO,
                                    final QuestionResponseReadDAO questionResponseDAO,
                                    final SleepStatsDAODynamoDB sleepStatsDAODynamoDB,
                                    final LightData lightData,
                                    final WakeStdDevData wakeStdDevData,
                                    final AccountPreferencesDAO accountPreferencesDAO,
                                    final CalibrationDAO calibrationDAO,
                                    final MarketingInsightsSeenDAODynamoDB marketingInsightsSeenDAODynamoDB) {
        this.accountDAO = accountDAO;
        this.deviceDataDAODynamoDB = deviceDataDAODynamoDB;
        this.deviceDAO = deviceDAO;
        this.senseColorDAO = senseColorDAO;
        this.scoreDAODynamoDB = scoreDAODynamoDB;
        this.insightsDAODynamoDB = insightsDAODynamoDB;
        this.insightsLastSeenDAO = insightsLastSeenDAO;
        this.trendsInsightsDAO = trendsInsightsDAO;
        this.questionResponseDAO = questionResponseDAO;
        this.sleepStatsDAODynamoDB = sleepStatsDAODynamoDB;
        this.lightData = lightData;
        this.wakeStdDevData = wakeStdDevData;
        this.accountPreferencesDAO = accountPreferencesDAO;
        this.calibrationDAO = calibrationDAO;
        this.marketingInsightsSeenDAODynamoDB = marketingInsightsSeenDAODynamoDB;
    }

    @Override
    public IRecordProcessor createProcessor() {
        final AccountInfoProcessor.Builder builder = new AccountInfoProcessor.Builder()
                .withQuestionResponseDAO(questionResponseDAO)
                .withMapping(questionResponseDAO);
        final AccountInfoProcessor accountInfoProcessor = builder.build();

        final InsightProcessor.Builder insightBuilder = new InsightProcessor.Builder()
                .withSenseDAOs(deviceDataDAODynamoDB, deviceDAO)
                .withSenseColorDAO(senseColorDAO)
                .withInsightsDAO(trendsInsightsDAO)
                .withDynamoDBDAOs(scoreDAODynamoDB, insightsDAODynamoDB, insightsLastSeenDAO, sleepStatsDAODynamoDB)
                .withAccountReadDAO(accountDAO)
                .withAccountInfoProcessor(accountInfoProcessor)
                .withLightData(lightData)
                .withWakeStdDevData(wakeStdDevData)
                .withPreferencesDAO(accountPreferencesDAO)
                .withCalibrationDAO(calibrationDAO)
                .withMarketingInsightsSeenDAO(marketingInsightsSeenDAODynamoDB);

        final InsightProcessor insightProcessor = insightBuilder.build();

        return new InsightsGenerator(accountDAO, deviceDAO, insightProcessor);
    }
}
