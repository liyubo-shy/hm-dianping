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
import com.hmdp.utils.RedisConstants;
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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误");
        }
        // 生产验证码
        String code = RandomUtil.randomNumbers(6);
        // 储存验证码到redis，以login:code:+phone作为key，TTL为2分钟
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
        log.debug("验证码:{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机格式错误");
        }

        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        // 从redis拿到验证码并与前端传来的验证码做判断
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            log.debug("验证码不存在{}", cacheCode);
            stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
            return Result.fail("验证码错误，请重新获取验证码");
        }

        // 查询用户是否存在，否则新增用户
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if (user == null) {
            user = creatUser(phone);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 生产token作为key，储存用户基础信息到redis
        String token = UUID.randomUUID().toString(true);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    private User creatUser(String phone) {
        User user = new User();
        String nickName = "user_" + RandomUtil.randomNumbers(6);
        user.setNickName(nickName);
        user.setPhone(phone);
        save(user);
        return user;
    }
}
