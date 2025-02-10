package org.nuxeo.ecm.platform.ui.web.keycloak.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.ui.web.keycloak.KeycloakAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.keycloak.segittur.model.HikariCPConfig;

public class DomainCreationListener implements EventListener {

	
	private static final String DOMAIN_TYPE = "Domain";

	private static final Logger log = LogManager.getLogger(DomainCreationListener.class);

	@Override
	public void handleEvent(Event event) {
		EventContext context = event.getContext();
		if (context instanceof DocumentEventContext) {
			DocumentEventContext docCtx = (DocumentEventContext) context;
			DocumentModel doc = docCtx.getSourceDocument();

			// Verificar si el documento es un dominio
			if (DOMAIN_TYPE.equals(doc.getType())) {
				
				//Evita la herencia de permisos
				CoreInstance.doPrivileged(KeycloakAuthenticationPlugin.DEFAULT_REPOSITORY_NAME, session -> {
					String documentPath = doc.getPathAsString();
					// Bloquear la herencia de permisos en el documento
					NuxeoOperationsManager.blockPermissionInheritance(session, documentPath);
				});
				
				//Crea grupo del Dominio
				log.info("Create Group {}", doc.getName());
				KeycloakAuthenticationPlugin.createGroupAndAssociateToDomainIfNotExist(doc.getName());
				
				
				//Crea los grupos asociado a Dominio-Servicio
				List<String> lServices=this.getAllowedServices(doc.getName());
				List<String> lServicesCopy = lServices.stream().collect(Collectors.toCollection(ArrayList::new));
				
				lServices.replaceAll(s -> doc.getName() + "_" + s);
				lServices.forEach(subGroup -> {
					log.info("Create subGroup {}", subGroup);
					KeycloakAuthenticationPlugin.createGroup(subGroup);
				});
				
//				KeycloakAuthenticationPlugin.associateSubgroupsToGroup(doc.getName(), lServices);
				
				
				//Crea grupos asociados a Dominio-Servicio-Rol
				lServicesCopy.forEach(service->{
					List<String> lRoles=this.getServiceRoles(service);
					
					lRoles.replaceAll(s -> doc.getName() + "_" + service + "_" + s);
					lRoles.forEach(subGroup -> {
						log.info("Create subGroup {}", subGroup);
						KeycloakAuthenticationPlugin.createGroup(subGroup);
					});
						
//					KeycloakAuthenticationPlugin.associateSubgroupsToGroup(doc.getName(), lRoles);	
				});
				
			}
		}

	}
	
	
	private List<String> getAllowedServices(String destination) {

		List<String> lServicios = new ArrayList<String>();
		
		
		try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query="SELECT s.identificador AS servicio FROM servicio AS s WHERE s.id IN (SELECT id_servicio FROM destino_servicio WHERE id_destino=(SELECT id FROM destino WHERE identificador=?))";
			
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				statement.setString(1, destination);
				
				ResultSet resultSet = statement.executeQuery();

				// Procesar los resultados
				while (resultSet.next()) {
					String idServicio = resultSet.getString("servicio");
					lServicios.add(idServicio);
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
		return lServicios;
	}
	

	
	private List<String> getServiceRoles(String service) {

		List<String> lRoles = new ArrayList<String>();
		
		
		try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query="SELECT r.nombre AS rol FROM rol AS r WHERE r.id IN (SELECT id_rol FROM rol_servicio WHERE id_servicio=(SELECT id FROM servicio WHERE identificador=?))";
			
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				statement.setString(1, service);
				ResultSet resultSet = statement.executeQuery();

				// Procesar los resultados
				while (resultSet.next()) {
					String idRol = resultSet.getString("rol");
					lRoles.add(idRol);
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
		return lRoles;
	}
	
	
	

}