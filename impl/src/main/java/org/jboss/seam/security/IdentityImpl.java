package org.jboss.seam.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Named;

import org.jboss.seam.security.events.AlreadyLoggedInEvent;
import org.jboss.seam.security.events.LoggedInEvent;
import org.jboss.seam.security.events.LoginFailedEvent;
import org.jboss.seam.security.events.NotAuthorizedEvent;
import org.jboss.seam.security.events.NotLoggedInEvent;
import org.jboss.seam.security.events.PostAuthenticateEvent;
import org.jboss.seam.security.events.PostLoggedOutEvent;
import org.jboss.seam.security.events.PreAuthenticateEvent;
import org.jboss.seam.security.events.PreLoggedOutEvent;
import org.jboss.seam.security.events.QuietLoginEvent;
import org.jboss.seam.security.management.IdentityManager;
import org.jboss.seam.security.permission.PermissionMapper;
import org.picketlink.idm.api.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity implementation for authorization and authentication via Seam security.
 * 
 * @author Shane Bryzak
 */
public @Named("identity") @SessionScoped class IdentityImpl implements Identity, Serializable
{
   private static final long serialVersionUID = 3751659008033189259L;
   
   private static final String RESPONSE_LOGIN_SUCCESS = "success";
   private static final String RESPONSE_LOGIN_FAILED = "failed";
   private static final String RESPONSE_LOGIN_EXCEPTION = "exception";
   
   protected static boolean securityEnabled = true;
   
   public static final String ROLES_GROUP = "Roles";
   
   Logger log = LoggerFactory.getLogger(IdentityImpl.class);

   @Inject private BeanManager manager;
   @Inject private Credentials credentials;
   @Inject private PermissionMapper permissionMapper;
   
   @Inject private IdentityManager identityManager;
   
   @Inject Instance<RequestSecurityState> requestSecurityState;
   
   private User user;

   /**
    * Contains a group name to group type:role list mapping of roles assigned 
    * during the authentication process
    */
   private Map<String,Map<String,List<String>>> preAuthenticationRoles = new HashMap<String,Map<String,List<String>>>();

   /**
    * Contains a group name to group type:role list mapping of roles granted 
    * after the authentication process has completed   
    */
   private Map<String,Map<String,List<String>>> activeRoles = new HashMap<String,Map<String,List<String>>>();
   
   /**
    * Map of group name:group type group memberships assigned during the 
    * authentication process
    */
   private Map<String,List<String>> preAuthenticationGroups = new HashMap<String,List<String>>();
   
   /**
    * Map of group name:group type group memberships granted after the 
    * authentication process has completed
    */
   private Map<String,List<String>> activeGroups = new HashMap<String,List<String>>();
   
   private transient ThreadLocal<Boolean> systemOp;
   
   /**
    * Flag that indicates we are in the process of authenticating
    */
   private boolean authenticating = false;
   
   public static boolean isSecurityEnabled()
   {
      return securityEnabled;
   }
   
   public static void setSecurityEnabled(boolean enabled)
   {
      securityEnabled = enabled;
   }
   
   public boolean isLoggedIn()
   {
      // If there is a user set, then the user is logged in.
      return user != null;
   }
   
   public boolean tryLogin()
   {      
      if (!authenticating && getUser() == null && credentials.isSet() && 
            !requestSecurityState.get().isLoginTried())
      {
         requestSecurityState.get().setLoginTried(true);
         quietLogin();
      }
      
      return isLoggedIn();
   }
   
   /**
    * Performs an authorization check, based on the specified security expression.
    * 
    * @param expr The security expression to evaluate
    * @throws NotLoggedInException Thrown if the authorization check fails and
    * the user is not authenticated
    * @throws AuthorizationException Thrown if the authorization check fails and
    * the user is authenticated
    */
   // QUESTION should we add the dependency on el-api for the sake of avoiding reinstantiating the VE?
   
   // TODO redesign restrictions system to be typesafe
   /*
   public void checkRestriction(ValueExpression expression)
   {
      if (!securityEnabled)
      {
         return;
      }
      
      if (!expressions.getValue(expression, Boolean.class))
      {
         if (!isLoggedIn())
         {
            manager.fireEvent(new NotLoggedInEvent());
            
            log.debug(String.format(
               "Error evaluating expression [%s] - User not logged in", expression.getExpressionString()));
            throw new NotLoggedInException();
         }
         else
         {
            manager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
               "Authorization check failed for expression [%s]", expression.getExpressionString()));
         }
      }
   }*/
   
   /**
    * Performs an authorization check, based on the specified security expression string.
    * 
    * @param expr The security expression string to evaluate
    * @throws NotLoggedInException Thrown if the authorization check fails and
    * the user is not authenticated
    * @throws AuthorizationException Thrown if the authorization check fails and
    * the user is authenticated
    */
   
   /*
   public void checkRestriction(String expr)
   {
      if (!securityEnabled)
      {
         return;
      }
      
      checkRestriction(expressions.createValueExpression(expr, Boolean.class).toUnifiedValueExpression());
   }*/

   public String login()
   {
      try
      {
         if (isLoggedIn())
         {
            // If authentication has already occurred during this request via a silent login,
            // and login() is explicitly called then we still want to raise the LOGIN_SUCCESSFUL event,
            // and then return.
            if (requestSecurityState.get().isSilentLogin())
            {
               manager.fireEvent(new LoggedInEvent(user));
               return RESPONSE_LOGIN_SUCCESS;
            }
            
            manager.fireEvent(new AlreadyLoggedInEvent());
            return RESPONSE_LOGIN_SUCCESS;
         }
         
         boolean success = authenticate();
                  
         if (success)
         {
            if (log.isDebugEnabled())
            {
               log.debug("Login successful for: " + credentials);
            }
            manager.fireEvent(new LoggedInEvent(user));
            return RESPONSE_LOGIN_SUCCESS;
         }
         
         credentials.invalidate();         
         return RESPONSE_LOGIN_FAILED;
      }
      catch (Exception ex)
      {
         if ( log.isDebugEnabled() )
         {
             log.debug("Login failed for: " + credentials, ex);
         }
         
         manager.fireEvent(new LoginFailedEvent(ex));
         
         return RESPONSE_LOGIN_EXCEPTION;
      }
   }
   
   public void quietLogin()
   {
      try
      {
         manager.fireEvent(new QuietLoginEvent());
          
         // Ensure that we haven't been authenticated as a result of the EVENT_QUIET_LOGIN event
         if (!isLoggedIn())
         {
            if (credentials.isSet())
            {
               authenticate();
               
               if (isLoggedIn())
               {
                  requestSecurityState.get().setSilentLogin(true);
               }
            }
         }
      }
      catch (Exception ex)
      {
         credentials.invalidate();
      }
   }
    
   protected boolean authenticate()
   {
      try
      {
         authenticating = true;
         
         user = null;
         
         preAuthenticate();
         
         Authenticator authenticator;
         
         Set<Bean<?>> authenticators = manager.getBeans(Authenticator.class);
         if (authenticators.size() == 1)
         {
            @SuppressWarnings("unchecked")
            Bean<Authenticator> authenticatorBean = (Bean<Authenticator>) authenticators.iterator().next();
            authenticator = (Authenticator) manager.getReference(authenticatorBean, Authenticator.class, manager.createCreationalContext(authenticatorBean));
         }
         else if (authenticators.size() > 1)
         {
            throw new IllegalStateException("More than one Authenticator bean found - please ensure " +
                  "only one Authenticator implementation is provided");
         }
         else
         {
            authenticator = null;
         }         
         
         boolean success = false;
         
         if (authenticator != null)
         {
            success = authenticator.authenticate();
         }
         else
         {
            // Otherwise if identity management is enabled, use it.
            if (identityManager != null)
            {            
               success = identityManager.authenticate(credentials.getUsername(),
                     credentials.getCredential());
               
               if (success)
               {
                  // TODO implement role population
                  //for (Role role : identityManager.getImpliedRoles(username))
                  //{
                    // idCallback.getIdentity().addRole(role.getRoleType().getName(), 
                      //     role.getGroup().getName(), role.getGroup().getGroupType());
                  //}
               }
            }
         }
         
         if (success)
         {
            user = new UserImpl(credentials.getUsername());
            postAuthenticate();
         }
         
         return success;
      }
      finally
      {
         // Set credential to null whether authentication is successful or not
         credentials.setCredential(null);
         authenticating = false;
      }
   }
   
   /**
    * Clears any roles added by calling addRole() while not authenticated.
    * This method may be overridden by a subclass if different
    * pre-authentication logic should occur.
    */
   protected void preAuthenticate()
   {
      preAuthenticationRoles.clear();
      manager.fireEvent(new PreAuthenticateEvent());
   }
   
   /**
    * Extracts the principal from the subject, and uses it to create the User object.  
    * This method may be overridden by a subclass if
    * different post-authentication logic should occur.
    */
   protected void postAuthenticate()
   {  
      if (isLoggedIn())
      {
         if (!preAuthenticationRoles.isEmpty())
         {
            for (String group : preAuthenticationRoles.keySet())
            {
               Map<String,List<String>> groupTypeRoles = preAuthenticationRoles.get(group);
               for (String groupType : groupTypeRoles.keySet())
               {
                  for (String roleType : groupTypeRoles.get(groupType))
                  {
                     addRole(roleType, group, groupType);
                  }
               }
            }
            preAuthenticationRoles.clear();
         }
         
         if (!preAuthenticationGroups.isEmpty())
         {
            for (String group : preAuthenticationGroups.keySet())
            {
               activeGroups.put(group, preAuthenticationGroups.get(group));
            }
            preAuthenticationGroups.clear();
         }         
      }
      
      manager.fireEvent(new PostAuthenticateEvent());
   }
   
   /**
    * Resets all security state and credentials
    */
   public void unAuthenticate()
   {
      user = null;      
      credentials.clear();
   }
   
   public void logout()
   {
      if (isLoggedIn())
      {
         PostLoggedOutEvent loggedOutEvent = new PostLoggedOutEvent(user);
         
         manager.fireEvent(new PreLoggedOutEvent());
         unAuthenticate();
         
         // TODO - invalidate the session
         // Session.instance().invalidate();
         
         manager.fireEvent(loggedOutEvent);
      }
   }

   public boolean hasRole(String roleType, String group, String groupType)
   {
      if (!securityEnabled) return true;
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return true;
      
      tryLogin();
      
      Map<String,List<String>> groupTypes = activeRoles.get(group);      
      List<String> roles = groupTypes != null ? groupTypes.get(groupType) : null;      
      return (roles != null && roles.contains(roleType));
   }
   
   public boolean addRole(String roleType, String group, String groupType)
   {
      if (roleType == null || "".equals(roleType) || group == null || "".equals(group) 
            || groupType == null || "".equals(groupType)) return false;
      
      Map<String,Map<String,List<String>>> roleMap = isLoggedIn() ? activeRoles : 
         preAuthenticationRoles;

      List<String> roleTypes = null;
      
      Map<String,List<String>> groupTypes = roleMap.get(group);
      if (groupTypes != null)
      {
         roleTypes = groupTypes.get(groupType);
      }
      else
      {
         groupTypes = new HashMap<String,List<String>>();
         roleMap.put(group, groupTypes);
      }
      
      if (roleTypes == null)
      {
         roleTypes = new ArrayList<String>();
         groupTypes.put(groupType, roleTypes);         
      }
      
      return roleTypes.add(roleType);
   }
   
   public boolean inGroup(String name, String groupType)
   {
      return activeGroups.containsKey(name) && activeGroups.get(name).contains(groupType);
   }
   
   public boolean addGroup(String name, String groupType)
   {
      if (name == null || "".equals(name) || groupType == null || "".equals(groupType))
      {
         return false;
      }
      
      Map<String,List<String>> groupMap = isLoggedIn() ? activeGroups : preAuthenticationGroups;
      
      List<String> groupTypes = null;
      if (groupMap.containsKey(name))
      {
         groupTypes = groupMap.get(name);
      }
      else
      {
         groupTypes = new ArrayList<String>();
         groupMap.put(name, groupTypes);
      }
      
      return groupTypes.add(groupType);
   }
   
   public void removeGroup(String name, String groupType)
   {
      if (activeGroups.containsKey(name))
      {
         activeGroups.get(name).remove(groupType);
      }
   }

   /**
    * Removes a role from the authenticated user
    * 
    * @param role The name of the role to remove
    */
   public void removeRole(String roleType, String group, String groupType)
   {      
      if (activeRoles.containsKey(group))
      {
         Map<String,List<String>> groupTypes = activeRoles.get(group);
         if (groupTypes.containsKey(groupType))
         {
            groupTypes.get(groupType).remove(roleType);
         }
      }
   }
   
   public void checkRole(String roleType, String group, String groupType)
   {
      tryLogin();
      
      if ( !hasRole(roleType, group, groupType) )
      {
         if ( !isLoggedIn() )
         {
            manager.fireEvent(new NotLoggedInEvent());
            throw new NotLoggedInException();
         }
         else
         {
            manager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
                  "Authorization check failed for role [%s:%s]", roleType, group));
         }
      }
   }
   
   public void checkPermission(Object target, String action)
   {
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return;
      
      tryLogin();
      
      if ( !hasPermission(target, action) )
      {
         if ( !isLoggedIn() )
         {
            manager.fireEvent(new NotLoggedInEvent());
            throw new NotLoggedInException();
         }
         else
         {
            manager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
                  "Authorization check failed for permission[%s,%s]", target, action));
         }
      }
   }
   
   public void filterByPermission(Collection<?> collection, String action)
   {
      permissionMapper.filterByPermission(collection, action);
   }
   
   public boolean hasPermission(Object target, String action)
   {
      if (!securityEnabled) return true;
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return true;
      if (permissionMapper == null) return false;
      if (target == null) return false;
      
      return permissionMapper.resolvePermission(target, action);
   }
   
   public synchronized void runAs(RunAsOperation operation)
   {
      User savedUser = getUser();
      
      if (systemOp == null)
      {
         systemOp = new ThreadLocal<Boolean>();
      }
      
      boolean savedSystemOp = systemOp.get();
      
      try
      {
         user = operation.getUser();         
         
         systemOp.set(operation.isSystemOperation());
         
         operation.execute();
      }
      finally
      {
         systemOp.set(savedSystemOp);
         user = savedUser;
      }
   }

   public void checkRestriction(String expr)
   {
      // TODO Auto-generated method stub
      
   }

   public User getUser()
   {
      return user;
   }
}
