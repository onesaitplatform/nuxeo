package org.nuxeo.ecm.platform.ui.web.keycloak.core;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;

public class ServerStartupComponent extends DefaultComponent {
	
	private static final Logger log = LogManager.getLogger(ServerStartupComponent.class);

    @Override
    public void start(ComponentContext context) {
    	log.info("=========================================================");
        log.info("Executing poststartup tasks");
        CreateSegitturGroups.executeStartupTasks();
        log.info("=========================================================");
    }

}