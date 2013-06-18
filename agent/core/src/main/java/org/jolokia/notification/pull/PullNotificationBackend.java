package org.jolokia.notification.pull;

import java.lang.management.ManagementFactory;
import java.util.Map;

import javax.management.*;

import org.jolokia.notification.*;
import org.jolokia.service.AbstractJolokiaService;
import org.jolokia.service.JolokiaContext;
import org.jolokia.util.JmxUtil;
import org.json.simple.JSONObject;

/**
 * Dummy implementation
 *
 * @author roland
 * @since 20.03.13
 */
public class PullNotificationBackend extends AbstractJolokiaService implements NotificationBackend {

    // Store for holding the notification
    private PullNotificationStore store;

    // maximal number of entries *per* notification subscription
    private int maxEntries = 100;

    // MBean name of this stored
    private ObjectName mbeanName;

    /**
     * Create a pull notification backend which will register an MBean allowing
     * to pull received notification
     *
     * @param order of this notification backend
     */
    public PullNotificationBackend(int order) {
        super(NotificationBackend.class,order);
    }

    /** {@inheritDoc} */
    public void init(JolokiaContext pContext) {
        String jolokiaId = pContext.getServerHandle().getJolokiaId();
        // TODO: Get configuration parameter for maxEntries
        store = new PullNotificationStore(maxEntries);
        mbeanName = JmxUtil.newObjectName("jolokia:type=NotificationStore,agent=" + jolokiaId);
        try {
            getMBeanServer().registerMBean(store, mbeanName);
        } catch (JMException e) {
            // TODO: Re-enable when notifications have been separated. If enabled, tests will fail.
            throw new IllegalArgumentException("Cannot register MBean " + mbeanName + " as notification pull store: " + e,e);
        }
    }

    /** {@inheritDoc} */
    public String getNotifType() {
        return "pull";
    }

    /** {@inheritDoc} */
    public BackendCallback subscribe(final NotificationSubscription pSubscription) {
        return new BackendCallback() {
            /** {@inheritDoc} */
            public void handleNotification(Notification notification, Object handback) {
                store.add(pSubscription,notification);
            }
        };
    }

    /** {@inheritDoc} */
    public void unsubscribe(String pClientId, String pHandle) {
        store.removeSubscription(pClientId, pHandle);
    }

    /** {@inheritDoc} */
    public void unregister(String pClientId) {
        store.removeClient(pClientId);
    }

    /** {@inheritDoc} */
    public Map<String, ?> getConfig() {
        JSONObject ret = new JSONObject();
        ret.put("store",mbeanName.toString());
        ret.put("maxEntries",maxEntries);
        return ret;
    }

    /** {@inheritDoc} */
    public void destroy() throws JMException {
        getMBeanServer().unregisterMBean(mbeanName);
    }

    // We use the platform MBeanServer for
    private MBeanServer getMBeanServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }
}