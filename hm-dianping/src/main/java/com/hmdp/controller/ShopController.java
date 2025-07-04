package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * Get Shop Info by shop id
     * @param id shop id
     * @return shop info
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * Add new shop info
     * @param shop shop params
     * @return shop id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        shopService.save(shop);
        return Result.ok(shop.getId());
    }

    /**
     * Update Shop Info
     * @param shop shop params
     * @return
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    /**
     * Query by Shop Type
     * @param typeId shop type id
     * @param current page number
     * @return shop list
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("x") Double x,
            @RequestParam("y") Double y
    ) {

        return shopService.queryShopByType(typeId, current, x,y);
    }

    /**
     * Query shop by name
     * @param name shop name
     * @param current page number
     * @return shop list
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
