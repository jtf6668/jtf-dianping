package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    //由于这个类没有被bean管理，所以不能直接获取stringRedisTemplate,要靠被bean管理的类传入
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程提示
        long threadId = Thread.currentThread().getId();
        //在redis中获取锁，设置键值成功就获取锁成功，获取失败就是失败
        //往redis中放入键值对并设置销毁时间
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);

        //直接返回success会有空指针风险，Boolean可能为null，自动拆箱后可能会返回空指针
        //return success;
        //如果为null也会返回false
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
