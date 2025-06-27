package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private IFollowService followService;


    @Override
    public Result queryHotBlog(Integer current) {

        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(
                blog -> {
                    // query blog user
                    this.queryBlogUser(blog);

                    // check if blog is already liked
                    isBlogLiked(blog);


                }
        );
        return Result.ok(records);
    }


    @Override
    public Result queryBlogById(Long id) {

        // check blog
        Blog blog = getById(id);

        if (blog == null) {
            return Result.fail("Blog not found");
        }

        // get user from blog
        queryBlogUser(blog);


        // check if blog is already liked
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // get user
        Long userId = Long.valueOf(4);

        // check if current user already like
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result likeBlog(Long id) {

        // get user
        Long userId = Long.valueOf(4);

        // check if current user already like
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // if no like yet, then like +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();

            if (isSuccess) {
                // save user to redis sorted set to ensure user cannot duplicate like
                // in redis = zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // if already like, cancel like, like -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();

            if (isSuccess) {
                // update redis
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }


        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        // get top 5 like user, redis = zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // get user id from the list
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // get user from user id
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD (id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // Get Login User
        UserDTO user = UserHolder.getUser();

        blog.setUserId(user.getId());

        // Save blog
        boolean isSuccess = save(blog);

        if(!isSuccess){
            return Result.fail("Fail to Add Blog");
        }

        // check blog's author's followers
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        // send the blog to feed
        for(Follow follow: follows){
            // get followers id
            Long userId = follow.getUserId();

            // send the blog to feed
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());


        }


        return Result.ok(blog.getId());


    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        // get current user
        Long userId = UserHolder.getUser().getId();
        
        // check inbox
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        key, 0, max, offset, 2
                );

        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }


        // decrypt data, find blog id, score(timestamp), offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;   // there will be at least one so set as 1
        for(ZSetOperations.TypedTuple<String> tuple: typedTuples){
            // Get id
            String idStr = tuple.getValue();

            long time = tuple.getScore().longValue();

            if(time == minTime){
                os++;
            }
            else{
                // Get score (timestamp)
                minTime = time;
                os = 1 ;
            }



        }

        String idStr = StrUtil.join(",", ids);
        // get blog from id
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();


        // get blog like and blog user
        for(Blog blog: blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        // return data
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);



        return Result.ok(r);
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
