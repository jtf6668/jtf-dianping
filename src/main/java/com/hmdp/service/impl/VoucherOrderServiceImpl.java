package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    //调用秒杀券查询
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //注入id生成器
    @Resource
    private RedisIdWorker redisIdWorker;

    //初始化lua脚本，使用静态代码块在类加载之前执行，只执行一遍，就不用每次释放锁时加载一遍，性能提升
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //设置lua脚本位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //由于子线程中不能获取ThreadLocal，所以要把代理对象设置成全局变量，在主线程中获取代理对象
    private IVoucherOrderService proxy;

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池,单线程进行
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //定义一个类让线程执行
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                //1.不断获取队列中的订单信息,take方法用于获取并且删除队列的头部，如果队列无元素则等待直到有元素可用
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }



    @PostConstruct//注解含义：一个方法在完成依赖注入后需要执行。当一个类被实例化并且所有的依赖被注入后执行
    private void init(){
        //线程池开始不断运行VoucherOrderHandler
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //线程任务


    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断获取锁是否成功
        if(!isLock){
            //获取锁失败
            log.error("不允许重复下单");
            return;
        }
        proxy.creatVoucherOrder(voucherOrder);
    }



    /**
     * 用户抢购秒杀券，将秒杀券订单信息保存到数据库中
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本，查看有没有购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString()
        );

        int r = result.intValue();
        if(r != 0){
            //没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //判断结果为0
        //保存id信息到堵塞队列中
        //TODO 堵塞队列
        //订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);

        //代金券id
        voucherOrder.setVoucherId(voucherId);

        //将订单信息放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    /**
     * 在一人一单时查询不能使用乐观锁（乐观锁通过比较数据上锁，要修改数据才能比较数据，这里比较不了），只能加悲观锁
     *
     * @param voucherOrder
     */
    @Transactional
    //public synchronized Result creatVoucherOrder(Long voucherId) {//这里加锁范围过大，只需针对用户id加锁即可
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单,获取用户id
        Long userId = voucherOrder.getUserId();

        //给用户加锁,用户id值一样的加一把锁，注意，每次来的对象都是不一样的，toString的底层也是不一样，所以要用intern方法
        //这个锁的意思是锁定这个用户，下面这段函数没执行完下一个相同id的请求不能加入下段代码，而其他不同id的用户可以进来，当这段代码执行完后才能释放锁，下一次这个用户id才可以进来。
       // synchronized (userId.toString().intern()) {//用于这里加了事务，事务在锁释放后提交，这时还没有写入数据库，如果在事务提交前锁释放后这个时间段有其他线程进来，依然会出现并发问题。所以锁不能放在这里
            query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            //判断该用户订单是否已经存在，即该用户是否购买过该优惠券
            if (count() > 0) {
                //已经购买过了
                log.error("用户已经购买过了");
                return ;
            }

            //5.库存充足，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")//set stock = stock - 1
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();//where id = ? and stock = ?
            //判断扣减是否成功
            if (!success) {
                log.error("库存不足");
                return ;
            }
            //7.保存订单
            save(voucherOrder);
    }
}
