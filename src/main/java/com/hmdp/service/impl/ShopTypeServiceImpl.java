package com.hmdp.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.Map;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String types = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        if(StrUtil.isNotBlank(types)){
            List<ShopType> shopTypes = JSONUtil.toList(types, ShopType.class);
            return Result.ok(shopTypes);
        }
        List<ShopType> list = query().list();
        if(list == null){
            return Result.fail("没有店铺列表信息");
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE, JSONUtil.toJsonStr(list));
        return Result.ok(list);
    }
}
