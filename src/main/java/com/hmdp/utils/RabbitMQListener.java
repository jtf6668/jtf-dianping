package com.hmdp.utils;

import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

@Component
public class RabbitMQListener {

        @RabbitListener(queues = "test.queues")
        public void listenSimpleQueueMessage(String msg) throws InterruptedException {
            System.out.println("spring 消费者接收到消息：【" + msg + "】");
        }

}
