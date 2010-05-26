package org.jboss.seam.security.management;

import java.util.List;

import org.picketlink.idm.api.Credential;
import org.picketlink.idm.api.Group;
import org.picketlink.idm.api.IdentityType;
import org.picketlink.idm.api.Role;

/**
 * Identity Management API, deals with user name/password-based identity management.
 * 
 * @author Shane Bryzak
 */
public interface IdentityManager
{  
   boolean createUser(String username, Credential credential);
   
   boolean deleteUser(String username);
   
   boolean enableUser(String username);
   
   boolean disableUser(String username);
   
   boolean changePassword(String username, Credential credential);
   
   boolean isUserEnabled(String username);
   
   boolean grantRole(String username, String roleType, Group group);
   
   boolean revokeRole(String username, String roleType, Group group);
   
   boolean createRoleType(String roleType);
   
   boolean deleteRoleType(String roleType);
     
   boolean userExists(String username);
   
   boolean roleTypeExists(String roleType);
   
   boolean createGroup(Group group);
   
   boolean deleteGroup(Group group);
   
   boolean addToGroup(String username, Group group);
   
   boolean removeFromGroup(String username, Group group);
   
   List<String> findUsers();
   
   List<String> findUsers(String filter);
   
   List<String> getRoleTypes();
   
   List<String> getGrantableRoles();
   
   /**
    * Returns a list of the roles that are explicitly granted to the specified user;
    * 
    * @param name The user for which to return a list of roles
    * @return List containing the names of the granted roles
    */
   List<Role> getGrantedRoles(String name);
   
   /**
    * Returns a list of roles that are either explicitly or indirectly granted to the specified user.
    * 
    * @param name The user for which to return the list of roles
    * @return List containing the names of the implied roles
    */
   List<Role> getImpliedRoles(String name);
   
   List<IdentityType> listRoleMembers(String roleType, Group group);
      
   boolean authenticate(String username, String password);
   
   IdentityStore getIdentityStore();
   
   void setIdentityStore(IdentityStore identityStore);
   
   IdentityStore getRoleIdentityStore();
   
   void setRoleIdentityStore(IdentityStore roleIdentityStore);
   
   boolean isEnabled();
}