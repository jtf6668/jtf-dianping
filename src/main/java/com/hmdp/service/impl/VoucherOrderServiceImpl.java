package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    //调用秒杀券查询
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //注入id生成器
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 用户抢购秒杀券，将秒杀券订单信息保存到数据库中
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠秒杀卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //在这加锁最合适，因为这里是事务提交完再释放锁
        //创建锁对象,锁的范围是id
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁，设置锁的超时时间，过期就删除这个键（释放锁）
        boolean isLock = lock.tryLock(1200);
        //判断锁是否获取成功
        if(!isLock){//获取失败
            return Result.fail("不允许重复下单");
        }
        try {
            //return creatVoucherOrder(voucherId);//这里的creatVoucherOrder(voucherId)是this.creatVoucherOrder(voucherId)，拿到的是VoucherOrderServiceImpl对象而非代理对象，没有事务功能
            //拿到代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        }finally {
            //业务完成，释放锁
            lock.unlock();
        }
    }

    /**
     * 在一人一单时查询不能使用乐观锁（乐观锁通过比较数据上锁，要修改数据才能比较数据，这里比较不了），只能加悲观锁
     * @param voucherId
     * @return
     */
    @Transactional
    //public synchronized Result creatVoucherOrder(Long voucherId) {//这里加锁范围过大，只需针对用户id加锁即可
    public Result creatVoucherOrder(Long voucherId) {
        //一人一单,获取用户id，在localThread中拿
        Long userId = UserHolder.getUser().getId();

        //给用户加锁,用户id值一样的加一把锁，注意，每次来的对象都是不一样的，toString的底层也是不一样，所以要用intern方法
        //这个锁的意思是锁定这个用户，下面这段函数没执行完下一个相同id的请求不能加入下段代码，而其他不同id的用户可以进来，当这段代码执行完后才能释放锁，下一次这个用户id才可以进来。
       // synchronized (userId.toString().intern()) {//用于这里加了事务，事务在锁释放后提交，这时还没有写入数据库，如果在事务提交前锁释放后这个时间段有其他线程进来，依然会出现并发问题。所以锁不能放在这里
            query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断该用户订单是否已经存在，即该用户是否购买过该优惠券
            if (count() > 0) {
                //已经购买过了
                return Result.fail("用户已经购买过了");
            }

            //5.库存充足，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")//set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0).update();//where id = ? and stock = ?
            //判断扣减是否成功
            if (!success) {
                return Result.fail("扣减失败");
            }

            //6.创建订单
            //6.1.订单id
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);

            //6.3.代金券id
            voucherOrder.setVoucherId(voucherId);

            //7.保存订单
            save(voucherOrder);

            //8.返回订单id
            return Result.ok(orderId);
    }
}
