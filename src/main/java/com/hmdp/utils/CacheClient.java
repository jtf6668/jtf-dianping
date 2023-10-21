package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 封装储存和查询redis的方法的类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意java对象序列化为json并储存到key为String类型的key的redis中，可以设置ttl
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意java对象序列化为json并储存到key为String类型的key的redis中，设置逻辑过期时间解决缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //封装到redisData中
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    /**
     * 根据key查询缓存,序列化指定类型，利用缓存空值的方法解决缓存穿透问题，使用泛型和function
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix +id;
        //从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if(StrUtil.isNotBlank(json)){
            //存在，直接返回
            return JSONUtil.toBean(json,type);
        }

        //判断命中的value是否为空值
        if(json != null){
            return null;
        }

        //redis中不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        //数据库中不存在，返回空，往redis中放value为空的键值对
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        }

        //如果数据库中存在，返回数据库中的数据，往redis中放数据库的数据信息
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit);

        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *根据key查询缓存，并序列化为指定类型，利用逻辑过期解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        //2.判断在redis中是否存在该信息,因为redis中已经提前初始化了信息，所以数据库中有的一定存在
        if(StrUtil.isBlank(Json)){
            //3.不存在，直接返回空信息
            return null;
        }

        //4命中，把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5查看店铺信息是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回
            return r;
        }
        //5.2过期，缓存重建

        //6缓存重建
        //6.1获取互斥锁
        String locKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(locKey);
        //6.2判断获取互斥锁是否成功
        if(isLock) {
            //TODO:开启二次检查，如果获取锁的时候缓存还没过期（已经被更新），就不用实现缓存重建
            //6.3成功，开启新线程（交给线程池），实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setLogicalExpire(key,r1,time,unit);
                    //释放锁
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //用于一定要释放锁，所以要设置try...catch...finally
                    unlock(locKey);
                }
            });
        }
        //7.无论有没有获得锁，都是立刻返回当前的店铺信息，重建过程交给独立线程完成
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //直接拆箱可能会导致空指针
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 在redis中将店铺信息加上店铺信息有效时间，形成逻辑有效时间
     * @param id
     * @param expireSecond
     */
}
