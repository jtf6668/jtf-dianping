package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //1.
        //Shop shop = queryWithPassThrough(id);
        //2使用工具类
        //2.1
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //2.2简写
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

//    /**
//     * 查询店铺信息，解决缓存击穿
//     * @param id
//     * @return
//     */
//    public Shop queryWithMutex(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis中查询店铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断在redis中是否存在真实数据
//        if(StrUtil.isNotBlank(shopJson)){
//            //3.是，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        //判断redis中的数据是否为空值，即shopJson不为空，但没有具体值
//        if(shopJson != null){
//            return null;
//        }
//
//        //4实现缓存重建
//        //4.1获取互斥锁
//        String lockKey = "lock:shop:" + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            //4.2判断获取锁是否成功
//            if(!isLock){
//                //4.3失败，休眠并重试
//                //休眠
//                Thread.sleep(50);
//                //重试
//                return queryWithMutex(id);
//            }
//            //4.4查询数据库
//            shop = getById(id);
//
//            // 模拟重建时的延迟
//            Thread.sleep(200);
//            if(shop == null){
//                //数据库中也不存在，将空值保存到redis缓存中，然后返回店铺不存在
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//
//            //存在，将店铺信息写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //释放锁
//            unlock(lockKey);
//        }
//
//        //7.返回用户信息
//        return shop;
//    }
//
//    /**
//     * 查询店铺信息，解决缓存穿透
//     * @param id
//     * @return
//     */
//    public Shop queryWithPassThrough(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis中查询店铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断在redis中是否存在真实数据
//        if(StrUtil.isNotBlank(shopJson)){
//            //3.是，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        //判断redis中的数据是否为空值，即shopJson不为空，但没有具体值
//        if(shopJson != null){
//            return null;
//        }
//        //4.不存在，查询数据库
//        Shop shop = getById(id);
//        if(shop == null){
//            //数据库中也不存在，将空值保存到redis缓存中，然后返回店铺不存在
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //存在，将店铺信息写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //7.返回用户信息
//        return shop;
//    }
//
//    /**
//     * 创建一个线程池
//     */
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    /**
//     * 用逻辑过期解决缓存击穿问题，此时不用考虑缓存穿透问题，因为redis中永远存在该店铺信息，不会过期
//     * @param id
//     * @return
//     */
//    public Shop queryWithLogicalExpire(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis中查询店铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断在redis中是否存在该店铺信息,因为redis中已经提前初始化了店铺信息，所以数据库中有的一定存在
//        if(StrUtil.isBlank(shopJson)){
//            //3.不存在，直接返回空信息
//            return null;
//        }
//
//        //4命中，把json反序列化成对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        //5查看店铺信息是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //5.1未过期，直接返回
//            return shop;
//        }
//        //5.2过期，缓存重建
//
//        //6缓存重建
//        //6.1获取互斥锁
//        String locKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(locKey);
//        //6.2判断获取互斥锁是否成功
//        if(isLock) {
//            //TODO:开启二次检查，如果获取锁的时候缓存还没过期（已经被更新），就不用实现缓存重建
//            //6.3成功，开启新线程（交给线程池），实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                    //释放锁
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //用于一定要释放锁，所以要设置try...catch...finally
//                    unlock(locKey);
//                }
//            });
//        }
//        //7.无论有没有获得锁，都是立刻返回当前的店铺信息，重建过程交给独立线程完成
//        return shop;
//    }
//
//    /**
//     * 设置互斥锁，用于防止缓存击穿，拿到互斥锁才能访问数据库
//     * @param key
//     * @return
//     */
//    private boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        //直接拆箱可能会导致空指针
//        return BooleanUtil.isTrue(flag);
//    }
//
//    /**
//     * 释放锁
//     * @param key
//     */
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }

    /**
     * 在redis中将店铺信息加上店铺信息有效时间，形成逻辑有效时间
     * @param id
     * @param expireSecond
     */
    public void saveShop2Redis(Long id,Long expireSecond) throws InterruptedException{
        //查询店铺数据
        Shop shop = getById(id);
        //模拟查询数据库花费时间
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //在现在的时间上加上一段时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
