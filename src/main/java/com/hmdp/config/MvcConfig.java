package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    //因为拦截器LoginInterceptor不是spring的类，所以用不了@bean和resource对象，又因为它先于容器初始化就执行了，所以不能加入容器中，
    //只能在这注入，然后用构造方法放入LoginInterceptor
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        //拦截需要用户登录的请求
        registry.addInterceptor(new LoginInterceptor( ))
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);

        //拦截所有请求，用于刷新令牌生效时间
        registry.addInterceptor(new RefreshInterceptor(stringRedisTemplate)).order(0);
    }
}
