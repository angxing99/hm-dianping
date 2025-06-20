package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_TYPE_CACHE_KEY;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        String key = SHOP_TYPE_CACHE_KEY;

        // 1. Check if cache exist in redis
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopTypeJson)){
            try {
                // 2. Parse JSON string to List<ShopType>
                List<ShopType> cacheList =
                        new ObjectMapper().readValue(shopTypeJson, new TypeReference<List<ShopType>>() {});
                return Result.ok(cacheList);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        // 3. Cache miss, query from DB
        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("No shop types found.");
        }


        try {
            // 4. Serialize and store in Redis
            String jsonStr = new ObjectMapper().writeValueAsString(typeList);
            stringRedisTemplate.opsForValue().set(key, jsonStr);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 5. Return result
        return Result.ok(typeList);
    }
}
