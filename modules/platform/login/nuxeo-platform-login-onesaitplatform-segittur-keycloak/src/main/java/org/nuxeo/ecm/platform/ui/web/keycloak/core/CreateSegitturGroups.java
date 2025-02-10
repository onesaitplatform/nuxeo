package org.nuxeo.ecm.platform.ui.web.keycloak.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.platform.ui.web.keycloak.KeycloakAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.keycloak.segittur.model.HikariCPConfig;
import org.nuxeo.runtime.transaction.TransactionHelper;

public class CreateSegitturGroups {
	
	private static final Logger log = LogManager.getLogger(CreateSegitturGroups.class);

	public static void executeStartupTasks() {
		//Crea el grupo público
		log.info("Try to create public group");
        createGroupIfNotExist(KeycloakAuthenticationPlugin.PUBLIC_GROUP_NAME);
        
        log.info("Look for Services in database");
        List<String> lServicios=new ArrayList<String>();
        //Crea un grupo por cada servicio
        try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query="SELECT s.identificador as identificador FROM servicio as s";
			
			
			try (Statement statement = connection.createStatement()) {
				ResultSet resultSet = statement.executeQuery(query);

				// Procesar los resultados
				while (resultSet.next()) {
					lServicios.add(resultSet.getString("identificador"));
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
        log.info("Try to create one group for every service");
        lServicios.forEach(servicio -> createGroupIfNotExist(servicio));
        
    }
	
	
	private static void createGroupIfNotExist(String groupName) {
		try {
			log.info("Try to create group {}", groupName);
			AtomicReference<DocumentModelList> groups = new AtomicReference<DocumentModelList>();

			CoreSessionProvider.executeWithCoreSession(KeycloakAuthenticationPlugin.DEFAULT_REPOSITORY_NAME, session -> {
				groups.set(NuxeoOperationsManager.searchGroup(groupName));
			});

			if (groups.get().size() == 0) {// El grupo no existe
				TransactionHelper.startTransaction();
				try {
					CoreSessionProvider.executeWithCoreSession(KeycloakAuthenticationPlugin.DEFAULT_REPOSITORY_NAME, session -> {
						// Llama al método para asociar el grupo al dominio
						NuxeoOperationsManager.createGroup(session, groupName, groupName, groupName);
						
						//Asigna permiso de lectura en la raiz para el grupo (para que le aparezca el arbol al usuario
						NuxeoOperationsManager.assignReadPermissionToRoot(session, groupName);
					});
				} catch (Exception e) {
					log.error("Error", e);
				} finally {
					// Finalizar la transacción (commit)
					TransactionHelper.commitOrRollbackTransaction();
				}
				log.info("Group {} created", groupName);
			}else {
				log.info("Group {} previously existed", groupName);
			}
		} catch (Exception e) {
			log.error("Error creating group", e);
		}
	}
}
