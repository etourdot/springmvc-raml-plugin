/*
 * Copyright 2002-2016 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.phoenixnap.oss.ramlapisync.verification.checkers;

import java.util.LinkedHashSet;
import java.util.Set;

import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.phoenixnap.oss.ramlapisync.naming.Pair;
import com.phoenixnap.oss.ramlapisync.parser.ResourceParser;
import com.phoenixnap.oss.ramlapisync.verification.Issue;
import com.phoenixnap.oss.ramlapisync.verification.IssueLocation;
import com.phoenixnap.oss.ramlapisync.verification.IssueSeverity;
import com.phoenixnap.oss.ramlapisync.verification.IssueType;
import com.phoenixnap.oss.ramlapisync.verification.RamlActionVisitorCheck;

/**
 * A checker that will check request and response media types.
 * 
 * @author Kurt Paris
 * @since 0.1.0
 *
 */
public class ActionContentTypeChecker implements RamlActionVisitorCheck {
	
	public static String REQUEST_BODY_MISSING = "Body required but not found in target";
	public static String RESPONSE_BODY_MISSING = "Response Body required but not found in target";
	/**
	 * Class Logger
	 */
	protected static final Logger logger = LoggerFactory.getLogger(ActionContentTypeChecker.class);

	@Override
	public Pair<Set<Issue>, Set<Issue>> check(ActionType name, Action reference, Action target, IssueLocation location, IssueSeverity maxSeverity) {
		logger.debug("Checking action " + name);
		Set<Issue> errors = new LinkedHashSet<>();
		Set<Issue> warnings = new LinkedHashSet<>();
		Issue issue;
		
		//Only apply this checker in the contract
		if (location.equals(IssueLocation.CONTRACT)) {
			return new Pair<>(warnings, errors);
		}
		
		//Request First
		//First lets check if we have defined a request media type.
		if (reference.getBody() != null && !reference.getBody().isEmpty()) {
			//lets check if we have a catch all on the implementation
			if (target.getBody() == null || target.getBody().isEmpty()) {
				issue = new Issue(maxSeverity, location, IssueType.MISSING, REQUEST_BODY_MISSING, reference.getResource(), reference);
				RamlCheckerResourceVisitorCoordinator.addIssue(errors, warnings, issue, REQUEST_BODY_MISSING);
			}
			if(target.getBody() != null && target.getBody().containsKey(ResourceParser.CATCH_ALL_MEDIA_TYPE)) {
				//catch all will be able to handle any request.
			} else {
				for (String key : reference.getBody().keySet()) {
					if (!target.getBody().containsKey(key)) {
						issue = new Issue(maxSeverity, location, IssueType.MISSING, REQUEST_BODY_MISSING, reference.getResource(), reference);
						RamlCheckerResourceVisitorCoordinator.addIssue(errors, warnings, issue, REQUEST_BODY_MISSING + " " + key);
					}
				}
			}			
		}
		
		//Now the response
		if (reference.getResponses() != null && !reference.getResponses().isEmpty() && reference.getResponses().containsKey("200")) {
			//successful response
			Response response = reference.getResponses().get("200");
			if (response.getBody() != null && !response.getBody().isEmpty()) {
				if (target.getResponses() == null || target.getResponses().isEmpty() || target.getResponses().get("200") == null) {
					issue = new Issue(maxSeverity, location, IssueType.MISSING, RESPONSE_BODY_MISSING, reference.getResource(), reference);
					RamlCheckerResourceVisitorCoordinator.addIssue(errors, warnings, issue, RESPONSE_BODY_MISSING);
				} else {
					//successful response
					Response targetResponse = target.getResponses().get("200");
					for (String key : response.getBody().keySet()) {						
						if (!targetResponse.getBody().containsKey(key)) {
							issue = new Issue(maxSeverity, location, IssueType.MISSING, RESPONSE_BODY_MISSING, reference.getResource(), reference);
							RamlCheckerResourceVisitorCoordinator.addIssue(errors, warnings, issue, RESPONSE_BODY_MISSING + " " + key);
						}
					}
				}
			}
				
		}
			
		return new Pair<>(warnings, errors);
	}
	

	
	
}
