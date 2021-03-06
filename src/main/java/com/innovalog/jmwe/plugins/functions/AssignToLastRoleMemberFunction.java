package com.innovalog.jmwe.plugins.functions;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.user.util.UserManager;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Category;
import org.ofbiz.core.entity.GenericValue;

import webwork.action.ServletActionContext;

import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFieldConstants;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.changehistory.ChangeHistory;
import com.atlassian.jira.issue.changehistory.ChangeHistoryManager;
import com.atlassian.jira.issue.util.IssueChangeHolder;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleActors;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.opensymphony.module.propertyset.PropertySet;
import com.opensymphony.workflow.WorkflowException;

// This post function will assign the issue to the first default user of the specified role
public class AssignToLastRoleMemberFunction extends AbstractPreserveChangesPostFunction
{
    private static final Category log = Category.getInstance(AssignToLastRoleMemberFunction.class);

    private final UserManager userManager;

    public AssignToLastRoleMemberFunction(UserManager userManager) {
        this.userManager = userManager;
    }

    protected void executeFunction(
            Map<String, Object> transientVars, Map<String, String> args,
            PropertySet ps, IssueChangeHolder holder
    ) throws WorkflowException
    {
    	HttpServletRequest request = ServletActionContext.getRequest();
    	if (request!=null)
    	{
	    	String[] assigneeSelected = request.getParameterValues("assignee");
	    	if( assigneeSelected != null && !assigneeSelected[0].equals("-1") && args.get("skipIfAssignee")!=null && ((String)args.get("skipIfAssignee")).equalsIgnoreCase("yes")) {
	    	    // the user explicitly selected an assignee (and not "Unassigned")
	    	    return;
	    	}
    	}
        
        Long projectRoleId= null;        
        String rawprojectRoleId=(String)args.get("jira.projectrole.id");
        if(StringUtils.isBlank(rawprojectRoleId))
        {
          log.warn("AssignToLastRoleMember not configured with a valid projectroleid. (no assignment will be made)");
          return;       
        }
        try
        {
        	projectRoleId = new Long(Long.parseLong(rawprojectRoleId));          
       
        }
        catch(NumberFormatException  e)
        {
          StringBuffer sb = new StringBuffer();
          log.warn(sb.append("AssignToLastRoleMember not configured with a valid projectroleid, the project role id: ").append(projectRoleId).append(" can not be parsed. (no assignment will be made)").toString());
          return;
       
        }
        
        ProjectRoleManager projectRoleManager=(ProjectRoleManager)ComponentManager.getComponentInstanceOfType(ProjectRoleManager.class);
        ProjectRole projectRole=projectRoleManager.getProjectRole(projectRoleId);
        if(projectRole== null)
        {
          StringBuffer sb = new StringBuffer();
          log.warn(sb.append("AssignToLastRoleMember is configured to assign to the last user in project role that doesn\'t exist: id is ").append(projectRoleId).append(" (no assignment will be made)").toString());
          return;       
        }
        
        //Get users in this role
        Collection users = null;
        User user = null;
        Issue genericIssue =  (Issue) transientVars.get("issue");
        ProjectRoleActors actors = projectRoleManager.getProjectRoleActors(projectRole, genericIssue.getProjectObject());
        if (actors != null) {
        	users = actors.getUsers();
        }
        
        if (users != null) {
        	//first check current assignee
        	if (args.get("includeCurrentAssignee") != null && ((String)args.get("includeCurrentAssignee")).equalsIgnoreCase("yes"))
        	{
    	        MutableIssue issue = (MutableIssue) transientVars.get("issue");        
                if (issue.getModifiedFields().containsKey(IssueFieldConstants.ASSIGNEE))
                {
    	            ModifiedValue mv = (ModifiedValue) issue.getModifiedFields().get(IssueFieldConstants.ASSIGNEE);
    				if (mv != null)
    					user = (User)mv.getOldValue();
    	            else
    	            	user = issue.getAssignee();
                }
                else
                	user = issue.getAssignee();
        		if (user!=null && users.contains(user))
        		{
        			// Assign the issue
        	        issue.setAssignee(user);
        			return;       			
        		}
        	}

        	ChangeHistoryManager changeHistoryManager = (ChangeHistoryManager)ComponentManager.getComponentInstanceOfType(ChangeHistoryManager.class);
        	//changeHistory represents each changeset of the issue. we get each field changed per changeset below in changeItemBeans
        	List changeHistory = changeHistoryManager.getChangeHistoriesForUser(genericIssue, null);

        	ChangeHistory changeHistoryItem = null;
        	List changeItemBeans = null;
        	for (int i = changeHistory.size() - 1; i >= 0; i--) {
        		user = null;
        		changeHistoryItem = (ChangeHistory) changeHistory.get(i);
        		log.debug("history change at " + changeHistoryItem.getTimePerformed().toString());
        		//changeItemBeans is a List of the fields that were modified in this issue change
        		changeItemBeans = changeHistoryItem.getChangeItems();
        		java.util.Iterator it = changeItemBeans.iterator();
        		//loop over all the fields updated in this change and look for a change in 'assignee'
        		while ( it.hasNext() ) {
        			GenericValue change = (GenericValue)it.next();
        			String changedField = change.getString("field");
        			/*
        			 * (GenericValue)change seems to have properties matching the columns of the changeitem db table
        			 * we're interested in change items where the field == 'assignee'
        			 * The old and new data is capture in two forms: oldvalue/oldstring and newvalue/newstring
        			 * The "value" appears to be the username, while the "string" is the user's full name
        			 * Rely on username as it can't be freely changed by users so shouldn't get out of synch with user profile data
        			 */
        			if ( changedField.equalsIgnoreCase("assignee") ) {
        				//the assignee was changed in this changeset. grab the user object and move on to see if this user is a member of the specified role
        				log.debug("AssignToLastRoleMember history says assignee was previously " + change.getString("oldvalue"));
        				//get a true User object based on the username
                        user = userManager.getUser(change.getString("oldvalue"));
        				//break out of the loop over fields changed in this changeset. NOT the loop over all changesets
        				break;
        			}
        		}
        		//if we have a real user and it is a member of the specified role
        		if (user != null && users.contains(user)) {
        			log.info("AssignToLastRoleMember assigning " + genericIssue.getKey() + " to: " + user.getName());
        			
        			// Assign the issue
        	        MutableIssue issue = (MutableIssue) transientVars.get("issue");        
        	        issue.setAssignee(user);
        			return;
        		}        		
        	}
        	
        	if (args.get("includeReporter") != null && ((String)args.get("includeReporter")).equalsIgnoreCase("yes"))
        	{
    	        MutableIssue issue = (MutableIssue) transientVars.get("issue");        
        		user = issue.getReporter();
        		if (user!=null && users.contains(user))
        		{
        			// Assign the issue
        	        issue.setAssignee(user);
        			return;       			
        		}
        	}
        	
        	StringBuffer sb = new StringBuffer();
    		log.warn(sb.append("AssignToRoleMember is configured to assign to the last user in project role ").append(projectRole.getName()).append(". There are was no such user found in the change history. (no assignment will be made)").toString());
    		return;
    		
        } else {
        	StringBuffer sb = new StringBuffer();
            log.warn(sb.append("AssignToRoleMember is configured to assign to the last user in project role ").append(projectRole.getName()).append(". There are no users in that role. (no assignment will be made)").toString());
            return;  
        }

    }
    
}
