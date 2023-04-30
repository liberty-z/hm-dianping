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

/**
 * @author ZhaiLibo
 * @date 2023/4/30 -16:06
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //用构造方法注入
    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //传入的参数转化为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public <R,ID>R queryWithPassThrough(
            String keyPrefix, ID id,Class<R> type, Function<ID, R> dbFallback,Long time,TimeUnit unit){

        String key=keyPrefix+id;
        //1.从redis中查询店铺缓存
        String json= stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            R r= JSONUtil.toBean(json, type);
            return r;
        }

        //缓存穿透解决：判断命中的是否是空值
        if(json!=null){
            //返回一个错误信息
//            return Result.fail("店铺信息不存在");
            return null;
        }

        //4.不存在，根据id查询
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if(r==null){
            //缓存穿透解决：将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis,//添加过期时间
        this.set(key,r,time,unit);
        //7.返回
        return r;

    }



    //逻辑过期时间 解决缓存击穿
    public <R,ID>R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {

        String key = keyPrefix + id;
        //1.从redis查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期，z在这个时间之前的是过期的
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        //注意:获取锁成功应该再次检测redis缓存是否过期，做DoubleCheck。如果存在则无需重建缓存。
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R newR = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //6.4返回过期的商铺信息
        return r;
    }



    //互斥锁解决缓存击穿
    public <R,ID>R queryWithMutex(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {

        String key = keyPrefix + id;
        //1.从redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }

        //缓存穿透解决：判断命中的是否是空值
        if (json != null) {
            //返回一个错误信息
//            return Result.fail("店铺信息不存在");
            return null;
        }

        //4.实现缓存重构
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;

        try {//Thread.sleep()需要抛出异常
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if (!isLock) {
                //4.3失败，则休眠重试
                Thread.sleep(50);
                //递归
                return queryWithMutex(keyPrefix,id,type,dbFallback,time,unit);
            }
            //4.4成功,根据id查询数据库
            r= dbFallback.apply(id);

            //5.不存在，返回错误
            if (r == null) {
                //缓存穿透解决：将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，写入redis,//添加过期时间
            this.set(key,r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        //7.返回
        return r;
    }


    /**
     * 创建锁：使用redis的string类型的setnx，这个命令不允许建立重复的数据，可以实现锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        //因为可能会有某些原因导致锁没有被删除，所以设置有效期将其自动删除
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //自动拆箱可能会出现空指针，先使用工具
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
