package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private RedissonClient redissonClient;


    @Override
    public Result seckillVoucher(Long voucherId) {

        // check voucher
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // check if the begin date is already start
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("Event is not started!");
        }

        // check if the sec kill is already end
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("Event already finished");
        }

        // check if enough stock
        if(voucher.getStock() < 1){
            // if not enough, return error
            return Result.fail("Not enough stock");
        }

        Long userId = Long.valueOf(4);

        // This part only work on one system if have multiple system will then fail
        // use intern to ensure that the value even after toString is always same
        // use Pessimistic lock (悲观锁) here
//        synchronized (userId.toString().intern()){
//            // get transactional object
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }




        // Distributed lock
        // doing this ensure that even 2 JVM machine running at the same time can prevent them to placed the same order with same user ID
        // Build lock
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // Get lock
        boolean isLock = lock.tryLock();

        // check if get lock success
        if(!isLock){
            // get lock fail, return error
            return Result.fail("Only one order per user");
        }

        try {
            // Get transactional target

            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }
        finally {
            // unlock
            lock.unlock();
        }

    }


    @Transactional
    public  Result createVoucherOrder(Long voucherId){
        // Ensure only user can only buy one voucher
        Long userId = Long.valueOf(4);

        // Check order
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        // User already purchase, throw error
        if(count > 0){
            return Result.fail("User already purchased this voucher!");
        }

        // if enough, update stock
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if(!success){
            return Result.fail("Stock not enough!");
        }


        // build order
        VoucherOrder voucherOrder = new VoucherOrder();

        // Order id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);

        // User id
        //long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);

        // Voucher id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // return order id
        return Result.ok(orderId);


    }


}
