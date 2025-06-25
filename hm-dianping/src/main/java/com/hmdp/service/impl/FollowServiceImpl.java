package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        // get login user
        Long userId = Long.valueOf(4);
        String key = "follows:" + userId;

        // check if it is follow / unfollow

        // if follow, add data
        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                // save the follow user to redis set, sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key, follow.toString());
            }
        }
        // unfollow, delete
        else{
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));

            if(isSuccess){
                // remove follow user id form redis set
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }

        }


        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {

        // get login user
        Long userId = Long.valueOf(4);

        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {

        // get login user
        Long userId = Long.valueOf(4);

        String key = "follows:" + userId;
        String key2 = "follows:" + id;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
