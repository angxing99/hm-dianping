package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long id) {

        String key = CACHE_SHOP_KEY + id;

        // 1. Get cache from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. if cache exist, return cache
        if(StrUtil.isNotBlank(shopJson)){
           Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 3. if no cache found, query DB
        Shop shop = getById(id);

        // 4. if no data found from DB, return error

        if(shop == null){
            return Result.fail("Shop Not Found");
        }


        // 5. if data found, save to redis and return
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));


        return Result.ok(shop);
    }
}
