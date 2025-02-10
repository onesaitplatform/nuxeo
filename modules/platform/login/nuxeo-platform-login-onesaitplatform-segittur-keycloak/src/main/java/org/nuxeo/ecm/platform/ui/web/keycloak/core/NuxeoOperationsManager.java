package org.nuxeo.ecm.platform.ui.web.keycloak.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;


public class NuxeoOperationsManager {
	
	private static final Logger log = LogManager.getLogger(NuxeoOperationsManager.class);

	
    public static void createGroup(CoreSession session, String groupName, String groupLabel, String description) {
    	UserManager userManager = Framework.getService(UserManager.class);
    	
    	DocumentModel newGroup = userManager.getBareGroupModel();
        newGroup.setPropertyValue("group:groupname", groupName); // Asigna el nombre del grupo
        newGroup.setPropertyValue("group:grouplabel", groupLabel); // Asigna la etiqueta del grupo
        newGroup.setPropertyValue("group:description", description);  // Descripción del grupo
        newGroup.setPropertyValue("group:members", new ArrayList<>()); // Inicialmente sin miembros
        newGroup.setPropertyValue("group:subGroups", new ArrayList<>()); // Sin subgrupos
        newGroup.setPropertyValue("group:parentGroups", new ArrayList<>()); // Sin grupos padres

        
        // Persistir el grupo en el sistema
        userManager.createGroup(newGroup);
    }
    
    public static DocumentModelList searchGroup(String groupName) {
    	UserManager userManager = Framework.getService(UserManager.class);
    	
        if (userManager != null) {
        	return userManager.searchGroups(groupName);
        }
       throw new IllegalStateException("Impossible to create Domain");
    }
    
    public static List<DocumentModel> getDomains(CoreSession session) {
    	 String query = "SELECT * FROM Domain";
         return session.query(query);
    }
    
    public static List<DocumentModel> getDomain(CoreSession session, String domainPath) {
   	 String query = "SELECT * FROM Domain";
        return session.query(query);
   }
    
    public static boolean domainExist(CoreSession session, String domainPath) {
        try {
            session.getDocument(new PathRef(domainPath));
            return true; // El documento existe
        } catch (Exception e) {
            return false; // El documento no existe
        }
    }
    
    public static void associateGroupToDomain(CoreSession session, String domainPath, String groupName, String permission) {
        // Validar parámetros
        if (session == null) {
            throw new IllegalArgumentException("Session Can't be null");
        }
        if (domainPath == null || domainPath.isEmpty()) {
            throw new IllegalArgumentException("Domain Path can't be null or empty");
        }
        if (groupName == null || groupName.isEmpty()) {
            throw new IllegalArgumentException("Group Name can't be null or empty");
        }
        if (permission == null || permission.isEmpty()) {
            throw new IllegalArgumentException("Permission can't be null or empty");
        }

        // Obtener el documento del dominio a partir de su ruta
        DocumentModel domain;
        try {
            domain = session.getDocument(new PathRef(domainPath));
        } catch (Exception e) {
            throw new IllegalStateException("Domain not found in the path: " + domainPath, e);
        }

        // Obtener el ACP (Access Control Policy) actual del dominio
        ACP acp = domain.getACP();

        // Crear una nueva ACE (Access Control Entry) para el grupo
        ACE groupACE = new ACE(groupName, permission, true); // 'true' significa que el permiso es permitido

        // Obtener o crear la ACL local
        ACL acl = acp.getOrCreateACL(ACL.LOCAL_ACL);

        // Agregar la ACE a la ACL (verifica si ya existe para evitar duplicados)
        if (!acl.contains(groupACE)) {
            acl.add(groupACE);
        } else {
            log.info("The group yet has the permission in this domain");
        }

        // Establecer el ACP actualizado en el dominio
        domain.setACP(acp, true);

        // Guardar los cambios en el documento del dominio
        session.saveDocument(domain);

        log.info("The group '{}' has been associated to the domain '{}' with permission '{}'.", groupName, domainPath, permission);
    }
    
    
    public static boolean isGroupAssociatedWithDomain(CoreSession session, String domainPath, String groupName) {
        // Obtener el documento del dominio
        DocumentModel domain = session.getDocument(new PathRef(domainPath));

        // Obtener el ACP (Access Control Policy) del dominio
        ACP acp = domain.getACP();

        // Recorrer todas las ACLs y buscar una ACE con el grupo
        for (ACL acl : acp.getACLs()) {
            for (ACE ace : acl.getACEs()) {
                if (groupName.equals(ace.getUsername()) && ace.getPermission().equals(SecurityConstants.EVERYTHING)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    public static void assignReadPermissionToRoot(CoreSession session, String groupName) {
        // Obtener el documento raíz del repositorio
        DocumentModel root = session.getRootDocument();

        // Obtener las políticas de control de acceso (ACP) de la raíz
        ACP acp = root.getACP();

        // Crear una ACE (Access Control Entry) para el grupo con permiso READ
        ACE groupACE = new ACE(groupName, SecurityConstants.READ, true);
        
        // Agregar la ACE a la ACL local
        ACL acl = acp.getOrCreateACL(ACL.LOCAL_ACL);
        acl.add(groupACE);

        // Establecer el ACP actualizado en la raíz
        root.setACP(acp, true);

        // Guardar los cambios en el documento raíz
        session.saveDocument(root);

        log.info("Permission READ assigned to the group '" + groupName + "' in the root.");
    }
  
    
    public static void blockPermissionInheritance(CoreSession session, String documentPath) {
        // Crear el contexto de operación
        try (OperationContext context = new OperationContext(session)) {
            // Establecer el documento objetivo en el contexto
            context.setInput(session.getDocument(new org.nuxeo.ecm.core.api.PathRef(documentPath)));

            // Obtener el servicio de automatización
            AutomationService automationService = Framework.getService(AutomationService.class);

            // Invocar la operación "BlockPermissionInheritance"
            automationService.run(context, "Document.BlockPermissionInheritance");

            log.info("Permission Inheritance blocked in: " + documentPath);
        } catch (OperationException e) {
            throw new RuntimeException("Error invoking operation BlockPermissionInheritance", e);
        }
    }
    
    
//    public static void addSubGroupsToGroup(CoreSession session, String groupName, List<String> newSubGroups) {
//        // Obtener el servicio UserManager
//        UserManager userManager = Framework.getService(UserManager.class);
//
//        // Recuperar el modelo del grupo
//        DocumentModel groupModel = userManager.getGroupModel(groupName);
//
//        if (groupModel == null) {
//            throw new IllegalArgumentException("El grupo " + groupName + " no existe.");
//        }
//
//        // Obtener los subgrupos actuales
//        List<String> existingSubGroups = (List<String>) groupModel.getPropertyValue("group:subGroups");
//
//        // Combinar los subgrupos actuales con los nuevos
//        List<String> updatedSubGroups = new ArrayList<>();
//        if (existingSubGroups != null) {
//            updatedSubGroups.addAll(existingSubGroups);
//        }
//        updatedSubGroups.addAll(newSubGroups);
//
//        // Eliminar duplicados (opcional)
//        List<String> uniqueSubGroups = updatedSubGroups.stream().distinct().toList();
//
//        // Actualizar la propiedad de subgrupos
//        groupModel.setPropertyValue("group:subGroups", uniqueSubGroups.toArray(new String[0]));
//
//        // Guardar los cambios en el grupo
//        userManager.updateGroup(groupModel);
//
//        log.info("Subgrupos añadidos al grupo " + groupName + ": " + uniqueSubGroups);
//    }
    
    
   
}
