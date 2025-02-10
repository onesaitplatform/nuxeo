package org.nuxeo.ecm.platform.ui.web.keycloak.core;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;

public class CoreSessionProvider {

    public static void executeWithCoreSession(String repositoryName, SessionConsumer consumer) {
        CoreInstance.doPrivileged(repositoryName, session -> {
            consumer.accept(session);
        });
    }

    @FunctionalInterface
    public interface SessionConsumer {
        void accept(CoreSession session);
    }
}
