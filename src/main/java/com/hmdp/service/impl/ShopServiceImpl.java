package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 查询店铺信息，解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断在redis中是否存在真实数据
        if(StrUtil.isNotBlank(shopJson)){
            //3.是，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断redis中的数据是否为空值，即shopJson不为空，但没有具体值
        if(shopJson != null){
            return null;
        }

        //4实现缓存重建
        //4.1获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断获取锁是否成功
            if(!isLock){
                //4.3失败，休眠并重试
                //休眠
                Thread.sleep(50);
                //重试
                return queryWithMutex(id);
            }
            //4.4查询数据库
            shop = getById(id);

            // 模拟重建时的延迟
            Thread.sleep(200);
            if(shop == null){
                //数据库中也不存在，将空值保存到redis缓存中，然后返回店铺不存在
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //存在，将店铺信息写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }

        //7.返回用户信息
        return shop;
    }

    /**
     * 查询店铺信息，解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断在redis中是否存在真实数据
        if(StrUtil.isNotBlank(shopJson)){
            //3.是，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断redis中的数据是否为空值，即shopJson不为空，但没有具体值
        if(shopJson != null){
            return null;
        }
        //4.不存在，查询数据库
        Shop shop = getById(id);
        if(shop == null){
            //数据库中也不存在，将空值保存到redis缓存中，然后返回店铺不存在
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，将店铺信息写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回用户信息
        return shop;
    }

    /**
     * 设置互斥锁，用于防止缓存击穿，拿到互斥锁才能访问数据库
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //直接拆箱可能会导致空指针
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
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
