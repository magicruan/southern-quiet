package com.ai.southernquiet.notification.driver;

import com.ai.southernquiet.amqp.rabbit.AmqpAutoConfiguration;
import com.ai.southernquiet.notification.AmqpNotificationAutoConfiguration;
import com.ai.southernquiet.notification.NotificationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

import java.io.Serializable;

@SuppressWarnings("WeakerAccess")
public class AmqpNotificationPublisher<N extends Serializable> implements NotificationPublisher<N> {
    private final static Logger log = LoggerFactory.getLogger(AmqpNotificationPublisher.class);

    private RabbitTemplate rabbitTemplate;
    private MessageConverter messageConverter;
    private AmqpNotificationAutoConfiguration.Properties notificationProperties;
    private AmqpAutoConfiguration.Properties properties;

    private boolean enablePublisherConfirm;

    public AmqpNotificationPublisher(
        MessageConverter messageConverter,
        AmqpNotificationAutoConfiguration.Properties notificationProperties,
        AmqpAutoConfiguration.Properties properties,
        RabbitProperties rabbitProperties,
        RabbitConnectionFactoryBean factoryBean,
        ObjectProvider<ConnectionNameStrategy> connectionNameStrategy,
        boolean enablePublisherConfirm
    ) {
        this.messageConverter = messageConverter;
        this.notificationProperties = notificationProperties;
        this.properties = properties;
        this.enablePublisherConfirm = enablePublisherConfirm;

        ConnectionFactory connectionFactory = AmqpAutoConfiguration.rabbitConnectionFactory(rabbitProperties, factoryBean, connectionNameStrategy);
        ((CachingConnectionFactory) connectionFactory).setPublisherConfirms(enablePublisherConfirm);

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);

        if (enablePublisherConfirm) {
            rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                if (log.isDebugEnabled()) {
                    log.debug("接到publisher confirm: correlationData={}, ack={}, cause={}", correlationData, ack, cause);
                }
                else if (!ack) {
                    log.warn("通知发送确认失败: correlationData={}, cause={}", correlationData, cause);
                }
            });
        }
        this.rabbitTemplate = rabbitTemplate;
    }

    public MessageConverter getMessageConverter() {
        return messageConverter;
    }

    @Override
    public void publish(N notification, String source) {
        String prefix = notificationProperties.getNamePrefix();
        String exchange = getExchange(prefix, source);
        String routing = getRouting(prefix, source);

        MessagePostProcessor messagePostProcessor = message -> {
            MessageProperties properties = message.getMessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        };

        if (enablePublisherConfirm) {
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(
                    exchange,
                    routing,
                    notification,
                    messagePostProcessor
                );

                rabbitTemplate.waitForConfirmsOrDie(properties.getPublisherConfirmTimeout());
                return null;
            });
        }
        else {
            rabbitTemplate.convertAndSend(
                exchange,
                routing,
                notification,
                messagePostProcessor
            );
        }
    }
}
