package com.hmdp.utils;

/**
 * 分布式锁
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的时间，到达时间就释放锁
     * @return true代表获取锁成功，false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
