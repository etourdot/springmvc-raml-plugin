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
package com.phoenixnap.oss.ramlapisync.parser;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.MimeType;
import org.raml.model.ParamType;
import org.raml.model.Resource;
import org.raml.model.Response;
import org.raml.model.parameter.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.phoenixnap.oss.ramlapisync.data.ApiParameterMetadata;
import com.phoenixnap.oss.ramlapisync.javadoc.JavaDocEntry;
import com.phoenixnap.oss.ramlapisync.javadoc.JavaDocExtractor;
import com.phoenixnap.oss.ramlapisync.javadoc.JavaDocStore;
import com.phoenixnap.oss.ramlapisync.naming.Pair;
import com.phoenixnap.oss.ramlapisync.naming.SchemaHelper;

/**
 * Common service scanning functionality
 * 
 * @author Kurt Paris
 * @since 0.0.1
 *
 */
public abstract class ResourceParser {
	

	protected static final Logger logger = LoggerFactory.getLogger(ResourceParser.class);
	protected static final Pattern IGNORE_METHOD_REGEX = Pattern.compile(
			"^(equals|hashCode|clone|finalize|getClass|notify|notifyAll|toString|wait)", Pattern.CASE_INSENSITIVE);

	protected static final String CSRF_HEADER = "X-CSRF-TOKEN";
	
	public static final String CATCH_ALL_MEDIA_TYPE = "application/everything";

	protected JavaDocExtractor javaDocs;

	protected String version;

	protected String defaultMediaType;

	public ResourceParser(File javaDocPath, String version, String defaultMediaType) {
		this.version = version;
		this.defaultMediaType = defaultMediaType;
		this.javaDocs = new JavaDocExtractor(javaDocPath);
	}

	/**
	 * Loads the relevant methods from a service and extracts the information relevant to raml. Methods from the Object
	 * class are ignored
	 * 
	 * @param clazz
	 * @return
	 */
	private List<Resource> getMethodsFromService(Class<?> clazz, JavaDocStore javaDoc, Resource parentResource) {
		List<Resource> resources = new ArrayList<>();
		try {
			for (Method method : clazz.getMethods()) {
				if (!IGNORE_METHOD_REGEX.matcher(method.getName()).matches() && shouldAddMethodToApi(method)) {
					extractAndAppendResourceInfo(method, javaDoc.getJavaDoc(method), parentResource);
				}
			}
		} catch (NoClassDefFoundError nEx) {
			logger.error("Unable to get methods - skipping class " + clazz, nEx);
		}
		return resources;
	}

	/**
	 * Update JavaDoc extractor
	 * 
	 * @param javaDocs The java doc extractor to use (if any)
	 */
	public void setJavaDocs(JavaDocExtractor javaDocs) {
		this.javaDocs = javaDocs;
	}
	
	/**
	 * Method to check if a specific action type supports payloads in the body of the request
	 * 
	 * @param target The target Verb to check
	 * @return If true, the verb supports a payload in the request body
	 */
	public static boolean doesActionTypeSupportRequestBody(ActionType target) {
		return target.equals(ActionType.POST) || target.equals(ActionType.PUT);
	}

	/**
	 * Allows child Scanners to add their own logic on whether a method should be treated as an API or ignored
	 * @param method The method to inspect
	 * @return If true the method is annotated in such a way that it should be included in the raml
	 */
	protected abstract boolean shouldAddMethodToApi(Method method);

	/**
	 * Extracts parameters from a method call and attaches these with the comments extracted from the javadoc
	 * 
	 * @param apiAction The Verb of the action containing these parametes
	 * @param method The method to inspect
	 * @param parameterComments The parameter comments associated with these parameters
	 * @return A collection of parameters keyed by name
	 */
	protected Map<String, QueryParameter> extractQueryParameters(ActionType apiAction, Method method,
			Map<String, String> parameterComments) {
		// Since POST requests have a body we choose to keep all request data in one place as much as possible
		if (apiAction.equals(ActionType.POST) || method.getParameterCount() == 0) {
			return Collections.emptyMap();
		}
		Map<String, QueryParameter> queryParams = new LinkedHashMap<>();

		for (Parameter param : method.getParameters()) {
			if (isQueryParameter(param)) { // Lets skip resourceIds since these are going to be going in the URL
				ParamType simpleType = SchemaHelper.mapSimpleType(param.getType());

				if (simpleType == null) {
					queryParams.putAll(SchemaHelper.convertClassToQueryParameters(param,
							javaDocs.getJavaDoc(param.getType())));
				} else {
					// Check if we have comments
					String paramComment = parameterComments.get(param.getName());
					queryParams.putAll(SchemaHelper.convertParameterToQueryParameter(param, paramComment));
				}
			}
		}
		return queryParams;
	}

	/**
	 * Allows children to specify whether a parameter should be included when generating query parameters for a method
	 * @param param The the Parameter to be checked
	 * @return IF true this is a parameter that shuold be appended in the query string
	 */
	protected abstract boolean isQueryParameter(Parameter param);

	/**
	 * Allows children to specify which parameters within a method should be included in API generation
	 * 
	 * @param method The method to inspect
	 * @param includeUrlParameters If true this will include URL parameters
	 * @param includeNonUrlParameters If true this will include query and body params
	 * @return A list of request parameters for this API
	 */
	protected abstract List<ApiParameterMetadata> getApiParameters(Method method, boolean includeUrlParameters,
			boolean includeNonUrlParameters);

	/**
	 * Extracts the TOs and other parameters from a method and will convert into JsonSchema for inclusion in the body
	 * TODO refactor this code structure
	 * 
	 * @param apiAction The Verb of the action to be added
	 * @param method The method to be inspected
	 * @param parameterComments The parameter comments associated with these parameters
	 * @return A map of supported mime types for the request
	 */
	protected Map<String, MimeType> extractRequestBodyFromMethod(ActionType apiAction, Method method,
			Map<String, String> parameterComments) {

		if (!(doesActionTypeSupportRequestBody(apiAction)) || method.getParameterCount() == 0) {
			return Collections.emptyMap();
		}

		String comment = null;
		List<ApiParameterMetadata> apiParameters = getApiParameters(method, false, true);
		if (apiParameters.size() == 0) {
			//We only have url params it seems
			return Collections.emptyMap();
		}
		Pair<String, MimeType> schemaAndMime = extractRequestBody(method, parameterComments, comment, apiParameters);

		return Collections.singletonMap(schemaAndMime.getFirst(), schemaAndMime.getSecond());
	}

	/**
	 * Converts a method body into a request json schema and a mime type.
	 * TODO refactor this code structure
	 * 
	 * @param method The method to be used to get the request object
	 * @param parameterComments Associated JavaDoc for Parameters (if any)
	 * @param comment Main Method Javadoc Comment (if any)
	 * @param apiParameters The Parameters identifed from this method
	 * @return The Request Body as a schema and the associated mime type
	 */
	protected Pair<String, MimeType> extractRequestBody(Method method, Map<String, String> parameterComments,
			String comment, List<ApiParameterMetadata> apiParameters) {
		String schema;
		MimeType mimeType = new MimeType();
		if (apiParameters.size() == 1) {
			if (parameterComments != null && parameterComments.size() == 1) {
				comment = parameterComments.values().iterator().next();
			}
			ApiParameterMetadata ajaxParameter = apiParameters.get(0);
			schema = SchemaHelper.convertClassToJsonSchema(ajaxParameter, comment,
					javaDocs.getJavaDoc(ajaxParameter.getType()));
			if (StringUtils.hasText(ajaxParameter.getExample())) {
				mimeType.setExample(ajaxParameter.getExample());
			}
		} else {
			schema = "{ \"type\": \"object\", \n \"properties\": {\n"; // change to object where key = param name
			boolean first = true;
			for (ApiParameterMetadata param : apiParameters) {
				if (!first) {
					schema += ",\n";
				} else {
					first = false;
				}
				schema += "\"" + param.getName() + "\" : ";
				comment = "";
				if (parameterComments != null) {
					if (StringUtils.hasText(comment) && StringUtils.hasText(parameterComments.get(param.getJavaName()))) {
						comment = parameterComments.get(param.getJavaName());
					}
				}
				schema += SchemaHelper.convertClassToJsonSchema(param, comment, javaDocs.getJavaDoc(param.getType()));
			}

			schema += "}\n}";
		}
		mimeType.setSchema(schema);
		return new Pair<>(extractExpectedMimeTypeFromMethod(method), mimeType);
	}

	/**
	 * Allows children to add common headers to API methods (eg CSRF, Authorization)
	 * 
	 * @param action The action to be modified
	 * @param actionType The verb of the Action
	 * @param method The method to inspect for headers
	 */
	protected abstract void addHeadersForMethod(Action action, ActionType actionType, Method method);

	/**
	 * Queries the parameters in the Method and checks for an AjaxParameter Annotation with the resource Id flag enabled
	 * @param method The Method to be inspected
	 * @return A list of parameters that are exposed in the API 
	 */
	protected abstract ApiParameterMetadata[] extractResourceIdParameter(Method method);

	/**
	 * Extracts the http method (verb) as well as the name of the api call
	 * @param method The Method to inspect
	 * @return The Verb and Name (partial url if a resource needs to be created) of this method
	 */
	protected abstract Map<ActionType, String> getHttpMethodAndName(Method method);

	/**
	 * Extracts relevant info from a Java method and converts it into a RAML resource
	 * 
	 * @param method The Java method to introspect
	 * @param docEntry The associated JavaDoc (may be null)
	 * @param parentResource The Resource which contains this method
	 */
	protected abstract void extractAndAppendResourceInfo(Method method, JavaDocEntry docEntry, Resource parentResource);

	/**
	 * Checks is this api call is made directly on a resource without a trailing command in the URL. eg: POST on
	 * /myResource/
	 * 
	 * @param method The method to check
	 * @return If true then this is an Action/Verb on a resource collection
	 */
	protected abstract boolean isActionOnResourceWithoutCommand(Method method);

	/**
	 * Checks the annotations and return type that the method returns and the mime it corresponds to
	 * 
	 * @param method The method to inspect
	 * @return The mime type as string
	 */
	protected String extractMimeTypeFromMethod(Method method) {
		return defaultMediaType;
	}

	/**
	 * Checks the annotations and return type that the method returns and the mime it corresponds to
	 * @param method The method to inspect
	 * @return The mime type this method can work on
	 */
	protected String extractExpectedMimeTypeFromMethod(Method method) {
		return defaultMediaType;
	}

	/**
	 * Extracts the Response Body from a method in JsonSchema Format and embeds it into a response object based on the
	 * defaultMediaType
	 * @param method The method to inspect
	 * @param responseComment The JavaDoc (if any) for this response
	 * @return The response RAML model for this method (success only)
	 */
	protected Response extractResponseFromMethod(Method method, String responseComment) {
		Response response = new Response();
		String mime = extractMimeTypeFromMethod(method);
		MimeType jsonType = new MimeType(mime); // TODO this would be coolto annotate
												// TO/VO/DO/weO with the mime type
												// they represent and chuck it in here
		jsonType.setSchema(SchemaHelper.convertClassToJsonSchema(method.getGenericReturnType(), responseComment,
				javaDocs.getJavaDoc(method.getReturnType())));

		response.setBody(Collections.singletonMap(mime, jsonType));
		if (StringUtils.hasText(responseComment)) {
			response.setDescription(responseComment);
		} else {
			response.setDescription("Successful Response");
		}
		return response;
	}

	/**
	 * 
	 * Extracts class information from a (believe it or not) java class as well as the contained methods.
	 * 
	 * @param clazz The Class to be inspected
	 * @return The RAML Resource model for this class
	 */
	public Resource extractResourceInfo(Class<?> clazz) {
		logger.info("Parsing resource: " + clazz.getSimpleName() + " ");
		Resource resource = new Resource();
		resource.setRelativeUri("/" + getResourceName(clazz));
		resource.setDisplayName(clazz.getSimpleName()); // TODO allow the Api annotation to specify
														// this shit
		JavaDocStore javaDoc = javaDocs.getJavaDoc(clazz);
		String comment = javaDoc.getJavaDocComment(clazz);
		if (comment != null) {
			resource.setDescription(comment);
		}

		List<Resource> methodsFromService = getMethodsFromService(clazz, javaDoc, resource);
		for (Resource cResource : methodsFromService) {
			String relativeUri = cResource.getRelativeUri();
			if (resource.getResources().containsKey(relativeUri)) {
				resource.getResource(relativeUri).getActions().putAll(cResource.getActions());
			} else {
				cResource.setParentResource(resource);
				cResource.setParentUri(resource.getUri());
				resource.getResources().put(relativeUri, cResource);
			}
		}

		return resource;
	}

	/**
	 * Extracts the name of the resource that this class manages
	 * 
	 * @param clazz The Class to inspect
	 * @return The name of the resource this class maps to
	 */
	protected abstract String getResourceName(Class<?> clazz);

}
