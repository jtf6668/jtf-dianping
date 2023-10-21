package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于redis的id生成器
 */
@Component
public class RedisIdWorker {
    //开始的时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200l;

    //序列号的位数
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //获取当前时间戳
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1获取当前日期，精确到天,这样便于后面统计每日订单数量，也不会使得订单量过大
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //2.2自增
        long count = stringRedisTemplate.opsForValue().increment("icr" + keyPrefix + ":" + date);

        //拼接并返回
        //时间戳先左移32位，时间戳的前32位就变成0了，然后使用或运算，后面32位完全取决与序列号
        return timestamp << COUNT_BITS | count;
    }
}
