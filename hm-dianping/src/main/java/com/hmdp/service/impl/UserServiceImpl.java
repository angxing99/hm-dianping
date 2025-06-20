package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. Validate Phone number
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. Return error message if number not valid
            return Result.fail("Invalid Phone Format");
        }

        // 3. If valid, generate code
        String code = RandomUtil.randomNumbers(6); // generate 6 random numbers

        // 4. Save the code to Redis, use phone as key and code as value, set expire time to 2 minute
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        // 5. Send the code to user, will use log for now
        log.debug("Send SMS verification code to user, code: {}", code);


        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. Validate user phone number
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // return error if validation fail
            return Result.fail("Invalid phone format");
        }

        // Validate verification code
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            // return error if validation fail
            return Result.fail("Invalid Verification Code");
        }

        // 2. if valid, check user detail with phone number with mybatis plus
        User user = query().eq("phone", phone).one();
        if(user == null){
            // 3. if user not exist, create new user
          user =  createUserWithPhone(phone);
        }
        log.info("save user in service: {}", user);


        // 4. save user info to redis for new and existing user
        // 4.1 generate token
        String token = UUID.randomUUID().toString(true);

        // 4.2 convert user to hash map with String-only values
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())

        );

        // 4.3 save the data to redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        // 4.4 Set cache expire time
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }


    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // Create new user
        save(user);
        return user;
    }
}
