package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {
        // Cache Penetration
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES );

        // Cache Breakdown (Logical Expiration)
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop == null){
           return Result.fail("Shop Not Found");
       }

        return Result.ok(shop);
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // Cache Breakdown (Logical Expiration)
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. Get cache from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache not exist, return null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        // if cache exist
        // deserialize the json
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // check if expire
        if(expireTime.isAfter(LocalDateTime.now())){
            // not expire
            return shop;
        }

        // if expire need to rebuild
        // get mutex lock
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // check if the lock is lock
        if(isLock){
            // if success, then start another thread
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // cache rebuild
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                finally {
                    // release lock
                    unlock(lockKey);
                }
            });

        }

        // return expire shop info
        return shop;
    }


    // Cache Breakdown (Mutex Lock)
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. Get cache from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache exist, return cache
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // Check if the redis cache is null or empty
        if(shopJson != null){
            // return error
            return null;
        }

        String lockKey = "lock:shop:" + id;
        Shop shop = null;

        try {
            // 3.1 get mutex lock
            boolean isLock = tryLock(lockKey);

            // 3.2 check if get success
            if(!isLock){
                // 3.3 if fail, sleep and retry
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 3.4 if success, query db
            shop = getById(id);
            // Mock rebuild delay
            Thread.sleep(200);
            // 4. if no data found from DB, return error
            if(shop == null){
                // add null value to cache to prevent cache penetration
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 5. if data found, save to redis and return
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            // 6. release mutex lock
            unlock(lockKey);
        }

        return shop;
    }

    // Cache Penetration
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. Get cache from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache exist, return cache
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // Check if the redis cache is null or empty
        if(shopJson != null){
            // return error
            return null;
        }


        // 3. if no cache found, query DB
        Shop shop = getById(id);

        // 4. if no data found from DB, return error

        if(shop == null){
            // add null value to cache to prevent cache penetration
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);


            return null;
        }


        // 5. if data found, save to redis and return
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);


        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS );
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds){
        // 1. Query shop info
        Shop shop = getById(id);

        // 2. set the expire time
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. save to redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();

        if(id == null){
            return Result.fail("Shop Id is null");
        }


        // 1. update DB first
        updateById(shop);


        // 2. update cache
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }
}
