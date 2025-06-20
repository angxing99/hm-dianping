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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.service.impl.ShopServiceImpl.CACHE_REBUILD_EXECUTOR;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // Always work won't expire
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // set logic expire
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;

        // 1. Get cache from redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache exist, return cache
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }

        // Check if the redis cache is null or empty
        if(json != null){
            // return error
            return null;
        }

        // 3. if no cache found, query DB
        R r =dbFallback.apply(id);

        // 4. if no data found from DB, return error

        if(r == null){
            // add null value to cache to prevent cache penetration
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);


            return null;
        }

        // 5. if data found, save to redis and return
        this.set(key, r, time, unit);

        return r;
    }


    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;

        // 1. Get cache from redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache not exist, return null
        if(StrUtil.isBlank(json)){
            return null;
        }

        // if cache exist
        // deserialize the json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // check if expire
        if(expireTime.isAfter(LocalDateTime.now())){
            // not expire
            return r;
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
                    R r1 = dbFallback.apply(id);
                    this.setLogicalExpire(key,r1, time, unit);

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
        return r;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS );
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }









}
