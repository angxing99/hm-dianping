package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. Validate Phone number
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. Return error message if number not valid
            return Result.fail("Invalid Phone Format");
        }

        // 3. If valid, generate code
        String code = RandomUtil.randomNumbers(6); // generate 6 random numbers

        // 4. Save the code to session
        session.setAttribute("code", code);


        // 5. Send the code to user, will use log for now
        log.debug("Send SMS verification code to user, code: {}", code);


        return Result.ok();
    }
}
