package com.hmdp.utils;

/**
 * 锁的基本接口
 * @author ZhaiLibo
 * @date 2023/5/4 -17:27
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 所持有的超时时间，过期后自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */
    boolean tryLock(Long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
