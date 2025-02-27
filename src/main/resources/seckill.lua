--1.参数列表
--1.1.优惠券id
local voucherId = ARGV[1]
--1.2.用户id
local userId = ARGV[2]

--2.数据key
--2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本业务
--3.1.判断库存是否充足，此处库存使用string类型储存，所有这里用tonumber类型转换
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --库存不足
    return 1
end
--3.2.判断用户是否下单 ，查看订单的key
if(redis.call('sismember', orderKey, userId) == 1) then
    --3.3存在，说明是重复下单，返回二
    return 2
end
--3.4.扣库存，库存-1
redis.call('incrby',stockKey,-1)
--3.5.下单，保存订单信息
redis.call('sadd',orderKey,userId)
--成功，返回0
return 0;
