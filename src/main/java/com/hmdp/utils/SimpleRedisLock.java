package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //初始化lua脚本，使用静态代码块在类加载之前执行，只执行一遍，就不用每次释放锁时加载一遍，性能提升
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //设置lua脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //由于这个类没有被bean管理，所以不能直接获取stringRedisTemplate,要靠被bean管理的类传入
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程提示，线程id是每台服务器生成的一个自增的id，多台服务器的话会重复，所以加上uuid来区别唯一id
        //不同进程由uuid区分（即每个服务器一个进程，不同服务器由uuid区分），同一个进程由线程id区分线程，所以对id前缀的uuid可以用static final,一台服务器生成一个uuid即可
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //在redis中获取锁，设置键值成功就获取锁成功，获取失败就是失败
        //往redis中放入键值对并设置销毁时间
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        //直接返回success会有空指针风险，Boolean可能为null，自动拆箱后可能会返回空指针
        //return success;
        //如果为null也会返回false
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX +name),ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//
//        //获取锁中的线程id与现在的线程一不一样
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(threadId.equals(id)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
