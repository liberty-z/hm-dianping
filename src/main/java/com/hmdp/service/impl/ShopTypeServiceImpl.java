package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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

    /**
     * 自己写的，使用Redis中的
     * 返回店铺类型的集合
     * @return
     */
    @Override
    public Result queryTypeList() {
        String key = "cache:shopType:"+ "all";

        //1.从redis中查询店铺类型缓存
        String shopTypeJson= stringRedisTemplate.opsForValue().get(key);

        //2.判断redis是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            //3.redis中存在,直接返回,将字符串转换为list集合
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }


        //4.redis中不存在，到数据库中查询
        List<ShopType> typeList = query().list();

        //5.数据库中不存在，返回错误
        if(typeList==null){
            return Result.fail("店铺不存在");
        }

        //6.数据库中存在，写入redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));

        //7.返回
        return Result.ok(typeList);
    }
}
