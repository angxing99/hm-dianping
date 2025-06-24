package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Slf4j
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


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService seckill_order_executor = Executors.newSingleThreadExecutor();



    @PostConstruct
    private void init(){
        seckill_order_executor.submit(new VoucherOrderHandler());
    }



    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run(){
            while(true){
                try {
                    // get voucher info from message queue XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())

                    );

                    // check if get message success
                    if(list == null || list.isEmpty()){
                        // if fail, keep loop
                        continue;
                    }

                    // retrieve order record from list
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // if get success, place order
                    handleVoucherOrder(voucherOrder);

                    // ACK confirm  SACK stream,orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());

                } catch (Exception e) {
                  log.error("process order fail", e);
                  handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    // get pending list from message queue XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))

                    );

                    // check if get message success
                    if(list == null || list.isEmpty()){
                        // if fail, mean no error message, break
                        break;
                    }

                    // retrieve order record from list
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // if get success, place order
                    handleVoucherOrder(voucherOrder);

                    // ACK confirm  SACK stream,orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());

                } catch (Exception e) {
                    log.error("process pending list order fail", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }


        }
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//
//
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run(){
//            while(true){
//                // get voucher info from queue
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("process order fail", e);
//                }
//
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // Build lock
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // Get lock
        boolean isLock = lock.tryLock();

        // check if get lock success
        if(!isLock){
            // get lock fail, return error
            log.error("No duplicate order!");
            return;
        }

        try {
            // Get transactional target
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }
        finally {
            // unlock
            lock.unlock();
        }


    }

    private IVoucherOrderService proxy;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // Get user id
        Long userId = Long.valueOf(4);
        long orderId = redisWorker.nextId("order");

        // 1. start seckill lua script
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId));


        // 2. check if the result is 0
        int r = result.intValue();
        if(result != 0){
            // 3. if not 0 return error
            return Result.fail(r == 1 ? "Stock Not Enough!" : "Duplicate Order!");
        }

        // Get transactional target
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        return Result.ok(0);
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // Get user id
//        Long userId = Long.valueOf(4);
//
//        // 1. start seckill lua script
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
//
//
//        // 2. check if the result is 0
//        int r = result.intValue();
//        if(result != 0){
//            // 3. if not 0 return error
//            return Result.fail(r == 1 ? "Stock Not Enough!" : "Duplicate Order!");
//        }
//
//        // 3.1  else user can buy, save the order info
//
//        // build order
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        // Order id
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        // User id
//        //long userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);
//
//        // Voucher id
//        voucherOrder.setVoucherId(voucherId);
//
//
//        // Save order info to blocking queue
//        orderTasks.add(voucherOrder);
//
//        // Get transactional target
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//
//        return Result.ok(0);
//    }
//




    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        // Ensure only user can only buy one voucher
        Long userId =voucherOrder.getUserId();

        // Check order
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder)
                .count();

        // User already purchase, throw error
        if(count > 0){
            log.error("User already purchased this voucher!");
            return;
        }

        // if enough, update stock
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder)
                .gt("stock", 0)
                .update();

        if(!success){
            log.error("Stock not enough!");
            return;
        }


        save(voucherOrder);


    }


}
