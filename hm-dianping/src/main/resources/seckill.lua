-- 1.1. 优惠券id
local voucherId = ARGV[1]
-- 1.2. 用户id
local userId = ARGV[2]

local orderId = ARGV[3]



-- 2. 数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3. 判断库存是否充足
local stock = redis.call('get', stockKey)
if stock == false then
    return 1 -- 无库存信息，返回1
end

if tonumber(stock) <= 0 then
    return 1 -- 库存不足，返回1
end

-- 4. 判断用户是否已下单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2 -- 用户已下单，返回2
end

-- 5. 扣库存
redis.call('incrby', stockKey, -1)

-- 6. 下单（保存用户ID）
redis.call('sadd', orderKey, userId)


-- send message to queue
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId )


-- 7. 返回成功
return 0
