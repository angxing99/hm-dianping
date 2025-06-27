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
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // check if need to check with geolocation see if got pass in x and y
        if(x == null || y == null){
            // no location pass in, direct query db

            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

            return Result.ok(page.getRecords());

        }

        // calculate page params
        int from = (current -1 )* SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // check redis, use geodist to sort, and page
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .limit(end));

        // get shop by id
        if(results == null){
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            // already at last page, return empty
            return Result.ok();
        }


        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        // get from to end
        list.stream().skip(from).forEach(
                result -> {
                    // get shop id
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));

                    // get distance
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr, distance);
                }
        );

        // get shop by id
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD ( id," + idStr + ")").list();

        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());

        }
        return Result.ok(shops);
    }
}
