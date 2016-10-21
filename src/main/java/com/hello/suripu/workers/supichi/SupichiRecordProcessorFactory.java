package com.hello.suripu.workers.supichi;

import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.hello.suripu.core.speech.interfaces.SpeechResultIngestDAO;
import com.hello.suripu.core.speech.interfaces.SpeechTimelineIngestDAO;

/**
 * Created by ksg on 8/11/16
 */
public class SupichiRecordProcessorFactory implements IRecordProcessorFactory {

    private final AmazonS3 s3;
    private final String s3Bucket;
    private final SSEAwsKeyManagementParams s3SSEKey;
    private final SpeechTimelineIngestDAO speechTimelineIngestDAO;
    private final SpeechResultIngestDAO speechResultIngestDAO;
    private final Boolean debug;

    public SupichiRecordProcessorFactory(final String s3Bucket,
                                         final AmazonS3 s3,
                                         final SSEAwsKeyManagementParams s3SSEKey,
                                         final SpeechTimelineIngestDAO speechTimelineIngestDAO,
                                         final SpeechResultIngestDAO speechResultIngestDAO,
                                         final Boolean debug) {
        this.s3 = s3;
        this.s3Bucket = s3Bucket;
        this.s3SSEKey = s3SSEKey;
        this.speechTimelineIngestDAO = speechTimelineIngestDAO;
        this.speechResultIngestDAO = speechResultIngestDAO;
        this.debug = debug;
    }


    @Override
    public IRecordProcessor createProcessor() {
        return new SupichiRecordProcessor(s3, s3Bucket, s3SSEKey, speechTimelineIngestDAO, speechResultIngestDAO, debug);
    }
}
