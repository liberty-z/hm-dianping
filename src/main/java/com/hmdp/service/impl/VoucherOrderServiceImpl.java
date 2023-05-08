package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }


        Long userId = UserHolder.getUser().getId();
        //转换成String类型后，并不是同一个对象，不能成为同一个锁，需要使用intern()方法
        //synchronized (userId.toString().intern()){

        //创建锁对象（新增代码）
       // SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        //获取锁对象
//        boolean isLock = lock.tryLock(1200L);
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = lock.tryLock();
        //加锁失败
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);

        }finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.一人一单逻辑
        Long userId = UserHolder.getUser().getId();
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        log.info("count:" + count);

        //5.2判断是否存在
        if (count > 0) {
            //用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();


        if (!success) {
            //扣减库存
            return Result.fail("库存不足");
        }

        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1订单id，生产唯一id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //7.2用户id
        voucherOrder.setUserId(userId);

        //7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);

    }
}
