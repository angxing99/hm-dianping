package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock{

    private StringRedisTemplate stringRedisTemplate;

    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";


    @Override
    public boolean tryLock(long timeoutSec) {
        // Get Thread id
        long threadId = Thread.currentThread().getId();

        // Get lock
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);

        // here did not direct return success as success might be null, use this format can ensure either true or false will be return only
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {

        // unlock
        stringRedisTemplate.delete(KEY_PREFIX + name);

    }
}
