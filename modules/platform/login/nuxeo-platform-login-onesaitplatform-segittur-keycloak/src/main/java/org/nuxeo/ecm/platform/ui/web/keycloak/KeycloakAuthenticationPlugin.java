/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     François Maturel
 */
package org.nuxeo.ecm.platform.ui.web.keycloak;

import static org.nuxeo.ecm.platform.ui.web.keycloak.KeycloakUserInfo.KeycloakUserInfoBuilder.aKeycloakUserInfo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.keycloak.adapters.AdapterDeploymentContext;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.spi.AuthChallenge;
import org.keycloak.adapters.spi.AuthOutcome;
import org.keycloak.representations.AccessToken;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPluginLogoutExtension;
import org.nuxeo.ecm.platform.ui.web.keycloak.core.CoreSessionProvider;
import org.nuxeo.ecm.platform.ui.web.keycloak.core.NuxeoOperationsManager;
import org.nuxeo.ecm.platform.ui.web.keycloak.segittur.model.HikariCPConfig;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;
import org.nuxeo.usermapper.service.UserMapperService;

/**
 * Authentication plugin for handling auth flow with Keyloack
 *
 * @since 7.4
 */
public class KeycloakAuthenticationPlugin
		implements NuxeoAuthenticationPlugin, NuxeoAuthenticationPluginLogoutExtension {

	private static final Logger log = LogManager.getLogger(KeycloakAuthenticationPlugin.class);

	private static final String PROTOCOL_CLASSPATH = "classpath:";

	public static final String KEYCLOAK_CONFIG_FILE_KEY = "keycloakConfigFilename";

	public static final String KEYCLOAK_MAPPING_NAME_KEY = "mappingName";

	public static final String DEFAULT_MAPPING_NAME = "keycloak";

	private String keycloakConfigFile = PROTOCOL_CLASSPATH + "keycloak.json";

    private static final String ADMINISTRATORS_MAPPING_PLATFORM_ROLES_ENV = "ADMINISTRATORS_MAPPING_PLATFORM_ROLES";
    private static final String SERVICE_NAME_MAPPING_PLATFORM_ROLES_ENV = "SERVICE_NAME_MAPPING_PLATFORM_ROLES_ENV";
    private static final String DESTINATION_NAME_MAPPING_PLATFORM_ROLES_ENV = "DESTINATION_NAME_MAPPING_PLATFORM_ROLES_ENV";
    
       
	private static final String ADMINISTRATORS_GROUP_NAME = "administrators";
	public static final String PUBLIC_GROUP_NAME = "public";
	public static final String DEFAULT_REPOSITORY_NAME = "default";

	protected KeycloakAuthenticatorProvider keycloakAuthenticatorProvider;

	private ThreadLocal<KeycloakRequestAuthenticator> localKeycloakAuthenticator = new ThreadLocal<>();

	protected String mappingName = DEFAULT_MAPPING_NAME;

    private HashSet<String> hsAdministrators;
    private HashSet<String> hsServiceName;
    private HashSet<String> hsDestinationName;
    
	@Override
	public void initPlugin(Map<String, String> parameters) {
		log.info("INITIALIZE KEYCLOAK");

		if (parameters.containsKey(KEYCLOAK_CONFIG_FILE_KEY)) {
			keycloakConfigFile = PROTOCOL_CLASSPATH + parameters.get(KEYCLOAK_CONFIG_FILE_KEY);
		}

		if (parameters.containsKey(KEYCLOAK_MAPPING_NAME_KEY)) {
			mappingName = parameters.get(KEYCLOAK_MAPPING_NAME_KEY);
		}

		KeycloakDeployment kd;
		try (InputStream is = loadKeycloakConfigFile()) {
			kd = KeycloakNuxeoDeployment.build(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		keycloakAuthenticatorProvider = new KeycloakAuthenticatorProvider(new AdapterDeploymentContext(kd));
		log.info("Keycloak is using a per-deployment configuration loaded from: {}", keycloakConfigFile);

        this.hsAdministrators = new HashSet<String>();
        String administratorsEnv = System.getenv(ADMINISTRATORS_MAPPING_PLATFORM_ROLES_ENV);
        if(administratorsEnv != null && administratorsEnv.trim().length()>0) {
        	String[] roles = administratorsEnv.split(",");
        	for(int i=0; i < roles.length; i++) {
        		if(roles[i].trim().length()>0) {
        			this.hsAdministrators.add(roles[i].trim());
        		}
        	}
        }
        
        this.hsServiceName = new HashSet<String>();
        String serviceNameEnv = System.getenv(SERVICE_NAME_MAPPING_PLATFORM_ROLES_ENV);
        if(serviceNameEnv != null && serviceNameEnv.trim().length()>0) {
        	String[] roles = serviceNameEnv.split(",");
        	for(int i=0; i < roles.length; i++) {
        		if(roles[i].trim().length()>0) {
        			hsServiceName.add(roles[i].trim());
        		}
        	}
        }
        
        this.hsDestinationName = new HashSet<String>();
        String destinationNameEnv = System.getenv(DESTINATION_NAME_MAPPING_PLATFORM_ROLES_ENV);
        if(destinationNameEnv != null && destinationNameEnv.trim().length()>0) {
        	String[] roles = destinationNameEnv.split(",");
        	for(int i=0; i < roles.length; i++) {
        		if(roles[i].trim().length()>0) {
        			hsDestinationName.add(roles[i].trim());
        		}
        	}
        }


	}

	@Override
	public Boolean needLoginPrompt(HttpServletRequest httpRequest) {
		return Boolean.TRUE;
	}

	@Override
	public Boolean handleLoginPrompt(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String baseURL) {
		KeycloakRequestAuthenticator authenticator = localKeycloakAuthenticator.get();
		try {
			if (authenticator != null) {
				AuthChallenge challenge = authenticator.getChallenge();
				if (challenge != null) {
					if (authenticator.loginConfig == null) {
						authenticator.loginConfig = authenticator.request.getContext().getLoginConfig();
					}
					if (challenge.getResponseCode() >= 400) {
						if (authenticator.forwardToErrorPageInternal()) {
							return Boolean.TRUE;
						}
					}
					challenge.challenge(authenticator.getFacade());
				}
			}
			return Boolean.TRUE;
		} finally {
			localKeycloakAuthenticator.remove();
		}
	}

	@Override
	public List<String> getUnAuthenticatedURLPrefix() {
		// There are no unauthenticated URLs associated to login prompt.
		// If user is not authenticated, this plugin will have to redirect user to the
		// keycloak sso login prompt
		return null;
	}

	@Override
	public UserIdentificationInfo handleRetrieveIdentity(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		log.debug("KEYCLOAK will handle identification");

		KeycloakRequestAuthenticator authenticator = keycloakAuthenticatorProvider.provide(httpRequest, httpResponse);

		localKeycloakAuthenticator.set(authenticator);
		KeycloakDeployment deployment = keycloakAuthenticatorProvider.getResolvedDeployment();
		String keycloakNuxeoApp = deployment.getResourceName();

		AuthOutcome outcome = authenticator.authenticate();

		if (outcome == AuthOutcome.AUTHENTICATED) {
			AccessToken token = (AccessToken) httpRequest
					.getAttribute(KeycloakRequestAuthenticator.KEYCLOAK_ACCESS_TOKEN);

			KeycloakUserInfo keycloakUserInfo = getKeycloakUserInfo(token);

			UserMapperService ums = Framework.getService(UserMapperService.class);

			keycloakUserInfo.setRoles(getRoles(token, keycloakNuxeoApp));

			ums.getOrCreateAndUpdateNuxeoPrincipal(mappingName, keycloakUserInfo);

			return keycloakUserInfo;
		}
		return null;
	}

	@Override
	public Boolean handleLogout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		log.debug("KEYCLOAK will handle logout");

		String uri = keycloakAuthenticatorProvider.logout(httpRequest, httpResponse);
		try {
			httpResponse.sendRedirect(uri);
		} catch (IOException e) {
			String message = "Could note handle logout with URI: " + uri;
			log.error(message);
			throw new RuntimeException(message);
		}
		return Boolean.TRUE;
	}

	/**
	 * Get keycloak user's information from authentication token
	 *
	 * @param token the keycoak authentication token
	 * @return keycloak user's information
	 */
	private KeycloakUserInfo getKeycloakUserInfo(AccessToken token) {
		return aKeycloakUserInfo()
				// Required
				.withUserName(token.getEmail())
				// Optional
				.withFirstName(token.getGivenName()).withLastName(token.getFamilyName())
				.withCompany(token.getPreferredUsername())
				// The password is randomly generated has we won't use it
				.withPassword(UUID.randomUUID().toString()).build();
	}

	private Set<String> getRoles(AccessToken token, String keycloakNuxeoApp) {
		Set<String> allRoles = new HashSet<>();

		AccessToken.Access realmAccess = token.getRealmAccess();
		if (realmAccess != null && realmAccess.getRoles() != null) {
			allRoles.addAll(realmAccess.getRoles());
		}

		AccessToken.Access nuxeoResource = token.getResourceAccess(keycloakNuxeoApp);
		if (nuxeoResource != null) {
			Set<String> nuxeoRoles = nuxeoResource.getRoles();
			allRoles.addAll(nuxeoRoles);
		}
		
		//Rol para documentos públicos
		allRoles.add(PUBLIC_GROUP_NAME);
				
		//Roles para ADMINISTRADOR_PID, GESTOR_PID Y GESTOR_SERVICIO
		allRoles.addAll(this.getGeneralRoles(token));
		
		//Roles para Destinos
		allRoles.addAll(this.getDestinationOnlyRoles(token));
		
		//Roles para Destino-Servicio
		allRoles.addAll(this.getDestinationServiceRole(token));
		
		//Roles para Destino-Servicio-Rol
		allRoles.addAll(this.getDestinationServiceRoleRoles(token));
		
		
		return allRoles;
	}

	public static void createGroupAndAssociateToDomainIfNotExist(String groupName) {
		try {
			AtomicReference<DocumentModelList> groups = new AtomicReference<DocumentModelList>();

			CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
				groups.set(NuxeoOperationsManager.searchGroup(groupName));
			});

			if (groups.get().size() == 0) {// El grupo no existe
				CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
					NuxeoOperationsManager.createGroup(session, groupName, groupName, groupName);
				});
			}

			boolean startLocalTransaction=false;
			if(!TransactionHelper.isTransactionActive()) {
				TransactionHelper.startTransaction();
				startLocalTransaction=true;
			}
			try {
				CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
					if (NuxeoOperationsManager.domainExist(session, "/" + groupName) && !NuxeoOperationsManager
							.isGroupAssociatedWithDomain(session, "/" + groupName, groupName)) {

						// Asigna permiso de lectura en la raiz para el grupo (para que le aparezca el
						// arbol al usuario
						NuxeoOperationsManager.assignReadPermissionToRoot(session, groupName);

						// Asocia el Grupo al Dominio
						NuxeoOperationsManager.associateGroupToDomain(session, "/" + groupName, groupName,
								SecurityConstants.EVERYTHING);

					}
				});
			} catch (Exception e) {
				log.error("Error", e);
			} finally {
				if(startLocalTransaction) {
					// Finalizar la transacción (commit)
					TransactionHelper.commitOrRollbackTransaction();
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
	}
	
	
	
	public static void createGroup(String groupName) {
		try {
			AtomicReference<DocumentModelList> groups = new AtomicReference<DocumentModelList>();

			CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
				groups.set(NuxeoOperationsManager.searchGroup(groupName));
			});

			if (groups.get().size() == 0) {// El grupo no existe
				CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
					NuxeoOperationsManager.createGroup(session, groupName, groupName, groupName);
				});
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
	}

	/**
	 * Loads Keycloak from configuration file
	 *
	 * @return the configuration file as an {@link InputStream}
	 */
	@SuppressWarnings("resource")
	private InputStream loadKeycloakConfigFile() {

		if (keycloakConfigFile.startsWith(PROTOCOL_CLASSPATH)) {
			String classPathLocation = keycloakConfigFile.replace(PROTOCOL_CLASSPATH, "");

			log.debug("Loading config from classpath on location: {}", classPathLocation);

			// Try current class classloader first
			InputStream is = getClass().getClassLoader().getResourceAsStream(classPathLocation);
			if (is == null) {
				is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classPathLocation);
			}

			if (is != null) {
				return is;
			} else {
				String message = "Unable to find config from classpath: " + keycloakConfigFile;
				log.error(message);
				throw new RuntimeException(message);
			}
		} else {
			// Fallback to file
			try {
				log.debug("Loading config from file: {}", keycloakConfigFile);
				return new FileInputStream(keycloakConfigFile);
			} catch (FileNotFoundException fnfe) {
				String message = "Config not found for file: " + keycloakConfigFile;
				log.error(message);
				throw new RuntimeException(message, fnfe);
			}
		}
	}

	public void setKeycloakAuthenticatorProvider(KeycloakAuthenticatorProvider keycloakAuthenticatorProvider) {
		this.keycloakAuthenticatorProvider = keycloakAuthenticatorProvider;
	}
	
	
	private List<String> getGeneralRoles(AccessToken token) {
		List<String> allRoles=new ArrayList<String>();
		//Roles for Destinations
		if (token.getOtherClaims().containsKey("roles")) {
			List<Map> pidAuth = (List<Map>) token.getOtherClaims().get("roles");
			pidAuth.forEach(map -> {
				if (!map.containsKey("destination")) {// roles generales (no asociados a un Destino)
					if (map.get("roles") != null && ((List<String>) map.get("roles")).size() > 0) {
						List<String> tokenRoles=(List<String>) map.get("roles");
						tokenRoles.forEach(rol->{
							if(hsAdministrators.contains(rol)) { //ADMINISTRADOR_PID & GESTOR_PID
								allRoles.add(ADMINISTRATORS_GROUP_NAME);
							}else if(hsServiceName.contains(rol)) { //GESTOR_SERVICIO
								List<String> lGroups=this.getServicesForUser(token.getName());
								lGroups.forEach(group -> createGroup(group));
								allRoles.addAll(lGroups);
							}
						});
					}
				}
			});
		} else {
			log.error("Segittur Roles not available in token. Is Keycloak Security Plugin running?");
		}
		
		return allRoles;
	}
	
	
	private List<String> getServicesForUser(String user) {

		List<String> lServices = new ArrayList<String>();
		try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query = "SELECT identificador FROM servicio WHERE id IN (SELECT id_servicio FROM usuario_rol_servicio_destino where id_usuario=?)";
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				statement.setString(1, user);
				ResultSet resultSet = statement.executeQuery();

				// Procesar los resultados
				while (resultSet.next()) {
					String idServicio = resultSet.getString("identificador");
					lServices.add(idServicio);
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
		return lServices;
	}
	
	private List<String> getDestinationOnlyRoles(AccessToken token){
		List<String> allRoles=new ArrayList<String>();
		//Roles for Destinations
		if (token.getOtherClaims().containsKey("roles")) {
			List<Map> pidAuth = (List<Map>) token.getOtherClaims().get("roles");
			pidAuth.forEach(map -> {
				if (map.containsKey("destination")) {
					List<String> destinationRoles=(List<String>) map.get("roles");
					destinationRoles.forEach(rol->{
						if(hsDestinationName.contains(rol)) {
							String destination = (String) map.get("destination");
							this.createGroupAndAssociateToDomainIfNotExist(destination);
							allRoles.add(destination);		
						}
					});
				}
			});
		} else {
			log.error("Segittur Roles not available in token. Is Keycloak Security Plugin running?");
		}
		
		return allRoles;
	}
	
	
	private List<String> getDestinationServiceRole(AccessToken token){
		
		List<String> allRoles=new ArrayList<String>();
	
		//Roles for Destination-Role tuple
		Map<String, List<String>> mGroups=this.getDestinationServiceForUser(token.getName());
		mGroups.entrySet().forEach(entry->{
			String destination=entry.getKey();
			List<String> lRoles=entry.getValue();
			createGroup(destination);
			
			lRoles.replaceAll(s -> destination + "_" + s);
			lRoles.forEach(subGroup -> {
				createGroup(subGroup);
			});
			
//			associateSubgroupsToGroup(destination, lRoles);
			
			allRoles.addAll(lRoles);
			
		});
		
		return allRoles;
	}
	
	
	private Map<String, List<String>> getDestinationServiceForUser(String user) {

		Map<String, List<String>> mRoles = new HashMap<String, List<String>>();
		
		
		try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query="SELECT d.identificador as destino, s.identificador as servicio FROM destino as d, servicio as s"
					+ " WHERE d.id IN (SELECT id_destino FROM usuario_rol_servicio_destino WHERE id_usuario=?) AND s.id IN(SELECT id_servicio FROM usuario_rol_servicio_destino WHERE id_usuario=?)";
			
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				statement.setString(1, user);
				statement.setString(2, user);
				ResultSet resultSet = statement.executeQuery();

				// Procesar los resultados
				while (resultSet.next()) {
					String idDestino = resultSet.getString("destino");
					String idServicio = resultSet.getString("servicio");
					if(!mRoles.containsKey(idDestino)) {
						mRoles.put(idDestino, new ArrayList<String>());
					}
					mRoles.get(idDestino).add(idServicio);
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
		return mRoles;
	}
	
	private List<String> getDestinationServiceRoleRoles(AccessToken token){
		
		List<String> allRoles=new ArrayList<String>();
	
		Map<String, List<String>> mGroups=this.getDestinationServiceRolesForUser(token.getName());
		mGroups.entrySet().forEach(entry->{
			String destination=entry.getKey();
			List<String> lRoles=entry.getValue();
			createGroup(destination);
			
			lRoles.replaceAll(s -> destination + "_" + s);
			lRoles.forEach(subGroup -> {
				createGroup(subGroup);
			});
			
//			associateSubgroupsToGroup(destination, lRoles);
			
			allRoles.addAll(lRoles);
			
		});
		
		return allRoles;
	}
	
	
	
	private Map<String, List<String>> getDestinationServiceRolesForUser(String user) {

		Map<String, List<String>> mRoles = new HashMap<String, List<String>>();
		
		
		try (Connection connection = HikariCPConfig.getConnection()) {

			// Crear y ejecutar una consulta
			String query="SELECT d.identificador as destino, r.nombre as rol, s.identificador as servicio FROM destino as d, rol as r, servicio as s"
					+ " WHERE d.id IN (SELECT id_destino FROM usuario_rol_servicio_destino WHERE id_usuario=?) AND r.id IN (SELECT id_rol FROM usuario_rol_servicio_destino WHERE id_usuario=?) AND s.id IN(SELECT id_servicio FROM usuario_rol_servicio_destino WHERE id_usuario=?)";
			
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				statement.setString(1, user);
				statement.setString(2, user);
				statement.setString(3, user);
				ResultSet resultSet = statement.executeQuery();

				// Procesar los resultados
				while (resultSet.next()) {
					String idDestino = resultSet.getString("destino");
					String idRol = resultSet.getString("rol");
					String idServicio = resultSet.getString("servicio");
					if(!mRoles.containsKey(idDestino)) {
						mRoles.put(idDestino, new ArrayList<String>());
					}
					mRoles.get(idDestino).add(idServicio+"_"+idRol);
				}
			}

		} catch (Exception e) {
			log.error("Error", e);
		}
		return mRoles;
	}
	
//	public static void associateSubgroupsToGroup(String groupName, List<String> lSubgroups) {
//		
//		boolean startLocalTransaction=false;
//		if(!TransactionHelper.isTransactionActive()) {
//			TransactionHelper.startTransaction();
//			startLocalTransaction=true;
//		}
//		try {
//			CoreSessionProvider.executeWithCoreSession(DEFAULT_REPOSITORY_NAME, session -> {
//				NuxeoOperationsManager.addSubGroupsToGroup(session, groupName, lSubgroups);
//			});
//		} catch (Exception e) {
//			log.error("Error", e);
//		} finally {
//			if(startLocalTransaction) {
//				// Finalizar la transacción (commit)
//				TransactionHelper.commitOrRollbackTransaction();
//			}
//		}
//	}
}
