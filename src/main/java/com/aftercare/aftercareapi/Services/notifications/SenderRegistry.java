package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the one sender of each channel. Startup fails when a channel has none or several, except that a single
 * {@code @Primary} bean wins (tests replace the real sender that way).
 */
@Component
public class SenderRegistry {

    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);

    public SenderRegistry(ApplicationContext context) {
        Map<String, NotificationSender> all = BeanFactoryUtils.beansOfTypeIncludingAncestors(context, NotificationSender.class);
        ConfigurableListableBeanFactory bf = ((AbstractApplicationContext) context).getBeanFactory();
        for (NotificationChannel channel : NotificationChannel.values()) {
            List<String> names = new ArrayList<>();
            all.forEach((name, bean) -> {
                if (bean.channel() == channel) {
                    names.add(name);
                }
            });
            if (names.size() > 1) {
                names.removeIf(n -> !bf.getMergedBeanDefinition(n).isPrimary());
            }
            if (names.size() != 1) {
                throw new IllegalStateException("Exactly one notification sender is required for channel " + channel
                        + " (found " + (names.isEmpty() ? "none" : "several") + ")");
            }
            senders.put(channel, all.get(names.get(0)));
        }
    }

    public NotificationSender forChannel(NotificationChannel channel) {
        return senders.get(channel);
    }
}
