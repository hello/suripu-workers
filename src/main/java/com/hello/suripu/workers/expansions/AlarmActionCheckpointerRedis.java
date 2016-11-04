package com.hello.suripu.workers.expansions;

import com.google.common.collect.Maps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * Created by jnorgan on 11/3/16.
 */
public class AlarmActionCheckpointerRedis {

  private final static Logger LOGGER = LoggerFactory.getLogger(AlarmActionCheckpointerRedis.class);

  private final JedisPool jedisPool;

  private static final String GENERIC_EXCEPTION_LOG_MESSAGE = "error=jedis-connection-exception";
  private static final String ALARM_ACTION_ATTEMPTS_KEY = "alarm_actions";

  public AlarmActionCheckpointerRedis(final JedisPool jedisPool){
    this.jedisPool = jedisPool;
  }

  public Map<String, Long> getAllRecentActions(final Long oldestEventMillis) {
    final Map<String, Long> hashRingTimeMap = Maps.newHashMap();
    Jedis jedis = null;

    try {
      jedis = jedisPool.getResource();
      //Get all elements in the index range provided (score greater than oldest event millis)
      final Set<Tuple> allRecentAlarmActions = jedis.zrevrangeByScoreWithScores(ALARM_ACTION_ATTEMPTS_KEY, Double.MAX_VALUE, oldestEventMillis);

      for (final Tuple attempt:allRecentAlarmActions) {
        final String deviceHash = attempt.getElement();
        final long expectedRingTime = (long) attempt.getScore();
        hashRingTimeMap.put(deviceHash, expectedRingTime);
      }
    } catch (JedisDataException exception) {
      LOGGER.error("error=jedis-data-exception message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
    } catch (Exception exception) {
      LOGGER.error("error=redis-unknown-failure message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
    } finally {
      try {
        jedisPool.returnResource(jedis);
      } catch (Exception e) {
        LOGGER.error(GENERIC_EXCEPTION_LOG_MESSAGE + " message={}", e.getMessage());
      }
    }
    return hashRingTimeMap;
  }


  public Boolean recordAlarmActions(final Set<ExpansionAlarmAction> executedActions) {
    Jedis jedis = null;

    try {
      jedis = jedisPool.getResource();
      final Pipeline pipe = jedis.pipelined();
      pipe.multi();
      for(final ExpansionAlarmAction expAction : executedActions) {
        pipe.zadd(ALARM_ACTION_ATTEMPTS_KEY, expAction.expectedRingTime, expAction.getExpansionActionKey());
      }
      pipe.exec();
    }catch (JedisDataException exception) {
      LOGGER.error("error=jedis-data-exception message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
      return false;
    } catch(Exception exception) {
      LOGGER.error("error=redis-unknown-failure message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
      return false;
    }
    finally {
      try{
        jedisPool.returnResource(jedis);
      }catch (Exception e) {
        LOGGER.error(GENERIC_EXCEPTION_LOG_MESSAGE + " message={}", e.getMessage());
      }
    }
    LOGGER.debug("action=alarm_actions_recorded action_count={}", executedActions.size());
    return true;
  }

  public Long removeOldActions(final Long oldestEventMillis) {
    final Map<String, Long> hashRingTimeMap = Maps.newHashMap();
    Jedis jedis = null;

    try {
      jedis = jedisPool.getResource();
      //Get all elements in the index range provided (score greater than oldest event millis)
      return jedis.zremrangeByScore(ALARM_ACTION_ATTEMPTS_KEY, -1L, oldestEventMillis);
    } catch (JedisDataException exception) {
      LOGGER.error("error=jedis-data-exception message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
    } catch (Exception exception) {
      LOGGER.error("error=redis-unknown-failure message={}", exception.getMessage());
      jedisPool.returnBrokenResource(jedis);
    } finally {
      try {
        jedisPool.returnResource(jedis);
      } catch (Exception e) {
        LOGGER.error(GENERIC_EXCEPTION_LOG_MESSAGE + " message={}", e.getMessage());
      }
    }
    return 0L;
  }

}
