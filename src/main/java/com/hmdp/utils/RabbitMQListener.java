package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import javax.annotation.Resource;

@Component
public class RabbitMQListener {


    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

        @RabbitListener(queues = "test.queues")
        public void listenSimpleQueueMessage(VoucherOrder voucherOrder) throws InterruptedException, JsonProcessingException {
            System.out.println(voucherOrder.toString());
            voucherOrderService.handlerVoucherOrder(voucherOrder);
        }

}
