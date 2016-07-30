package com.hello.suripu.workers.pill.prox;

import com.google.common.io.LittleEndianDataInputStream;
import com.hello.suripu.core.models.TrackerMotion;
import org.joda.time.DateTime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

public class PillProxData {

    public final Long accountId;
    public final String pillId;
    public final Integer proxValue;
    public final Integer proxValue4;
    public final Integer reserved;

    public final DateTime ts;
    public final Integer offsetMillis = 0;   // TODO: LOL

    private PillProxData(final Long accountId, final String pillId, final Integer proxValue, final Integer proxValue4, final Integer reserved, final DateTime ts) {
        this.accountId = accountId;
        this.pillId = pillId;
        this.proxValue = proxValue;
        this.proxValue4 = proxValue4;
        this.ts = ts;
        this.reserved = reserved;
    }

    public static PillProxData create(final Long accountId, final String pillId, final Integer proxValue, final Integer proxValue4, final DateTime ts) {
        return new PillProxData(accountId, pillId, proxValue, proxValue4, 0, ts);
    }

    public static PillProxData fromEncryptedData(final String pillId, final byte[] encryptedProxData, final byte[] key, final DateTime sampleTime) {
        final byte[] decryptedProxData = decryptProxData(key, encryptedProxData);
        try (final LittleEndianDataInputStream littleEndianDataInputStream = new LittleEndianDataInputStream(new ByteArrayInputStream(decryptedProxData))) {
            final int proxValue = littleEndianDataInputStream.readInt();
            final int proxValue4 = littleEndianDataInputStream.readInt();
            final int reserved = littleEndianDataInputStream.readInt();
            return new PillProxData(0L, pillId, proxValue, proxValue4, reserved, sampleTime);
        } catch (IOException e) {
            throw new IllegalArgumentException("server can't parse prox data");
        }

    }

    public static byte[] decryptProxData(final byte[] key, final byte[] encryptedMotionData) {
        final byte[] nonce = Arrays.copyOfRange(encryptedMotionData, 0, 8);

        //final byte[] crc = Arrays.copyOfRange(encryptedMotionData, encryptedMotionData.length - 1 - 2, encryptedMotionData.length);  // Not used yet
        final byte[] encryptedRawMotion = Arrays.copyOfRange(encryptedMotionData, 8, encryptedMotionData.length);

        final byte[] decryptedRawMotion = TrackerMotion.Utils.counterModeDecrypt(key, nonce, encryptedRawMotion);
        return decryptedRawMotion;
    }
}


