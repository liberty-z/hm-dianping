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


import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号，前后端都进行校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号码不符合");
        }
        //3.符合，生成验证码，hutool中的类
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        //session.setAttribute("code", code);

        //4.保存验证码到redis中,RedisContants中设置常量，
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        //5.发送验证码
        log.debug("发送验证码成功，验证码{}", code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号,z每个请求都要独立的校验
        String phone=loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机格式错误！");
        }
/*//自己写的垃圾代码
        String code = loginForm.getCode();
        if (code==null||!code.equals(session.getAttribute("code"))) {
            return Result.fail("验证码错误");
        }*/
        // 3.校验验证码，z：code是魔法值，应该设置成常量，存储在一个固定的地方
       // Object checheCode = session.getAttribute("code");
        String checheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        String code = loginForm.getCode();
        if (checheCode == null || !checheCode.equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户 select * from tb_user where phone=?
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if(user==null){
            //不存在，则创建
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到session中
        //为了使减少返回的信息，使得数据更安全，利用hutool中的工具进行转化
       // session.setAttribute("user",BeanUtil.copyProperties(user, UserDTO.class));

        //7.保存用户到redis中
        //7.1随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将user对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //7.3存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // 7.4.设置token有效期,现在这样设置的是登录后30分钟就自动退出了
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MILLISECONDS);
        //8.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        //默认用户名称开头使用固定值，utils包下有设置常量的类，将所有常量都存放到了其中
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }
}
