package com.igsl.rest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.igsl.Log;
import com.igsl.config.Config;
import com.igsl.export.cloud.model.Linked;
import com.igsl.export.cloud.model.Paged;

public class RESTUtil {
	private static final Logger LOGGER = LogManager.getLogger(RESTUtil.class);
	
	public static final String ENCODDING = "ASCII";	
	public static final String SCHEME = "https://";

	// Request throttle
	private static long lastCheck = System.currentTimeMillis();
	private static Float allowance = null;
	
	private static final ObjectMapper OM = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private static final JacksonJsonProvider JACKSON_JSON_PROVIDER = 
			new JacksonJaxbJsonProvider()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(SerializationFeature.INDENT_OUTPUT, true);

	public static MultivaluedMap<String, Object> getCloudAuthenticationHeader(Config config) throws Exception {
		MultivaluedMap<String, Object> result = new MultivaluedHashMap<>();
		String headerValue = "Basic " + 
				Base64.getEncoder().encodeToString(
						(config.getCloud().getUserName() + ":" + config.getCloud().getApiToken())
							.getBytes(ENCODDING));
		List<Object> values = new ArrayList<>();
		values.add(headerValue);
		result.put("Authorization", values);
		return result;
	}

	public static MultivaluedMap<String, Object> getDCJiraAuthenticationHeader(Config config) throws Exception {
		MultivaluedMap<String, Object> result = new MultivaluedHashMap<>();
		String headerValue = "Basic " + 
				Base64.getEncoder().encodeToString(
						(config.getDcExport().getJiraUser() + ":" + config.getDcExport().getJiraPassword())
							.getBytes(ENCODDING));
		List<Object> values = new ArrayList<>();
		values.add(headerValue);
		result.put("Authorization", values);
		return result;
	}
	
	public static MultivaluedMap<String, Object> getDCConfluenceAuthenticationHeader(Config config) throws Exception {
		MultivaluedMap<String, Object> result = new MultivaluedHashMap<>();
		String headerValue = "Basic " + 
				Base64.getEncoder().encodeToString(
						(config.getDcExport().getConfluenceUser() + ":" + config.getDcExport().getConfluencePassword())
							.getBytes(ENCODDING));
		List<Object> values = new ArrayList<>();
		values.add(headerValue);
		result.put("Authorization", values);
		return result;
	}
	
	public static Response webRequest(
			Config config,
			String scheme,
			String domain,
			String path,
			String method,
			Collection<Cookie> cookies,
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			int... successStatuses) throws Exception {
		Client client = ClientBuilder.newClient();
		client.register(JACKSON_JSON_PROVIDER);
		URI uri = new URI(scheme + "://" + domain).resolve(path);
		WebTarget target = client.target(uri);
		Log.debug(LOGGER, "uri: " + uri.toASCIIString() + " " + method);
		if (queryParameters != null) {
			for (Map.Entry<String, Object> query : queryParameters.entrySet()) {
				Log.debug(LOGGER, "query: " + query.getKey() + " = " + query.getValue());
				target = target.queryParam(query.getKey(), query.getValue());
			}
		}
		Builder builder = target.request();
		if (headers != null) {
			builder = builder.headers(headers);
			for (Map.Entry<String, List<Object>> header : headers.entrySet()) {
				for (Object o : header.getValue()) {
					Log.debug(LOGGER, "header: " + header.getKey() + " = " + o);
				}
			}
		}
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				builder = builder.cookie(cookie);
			}
		}
		Response response = null;
		switch (method) {
		case HttpMethod.DELETE:
			response = builder.delete();
			break;
		case HttpMethod.GET:
			response = builder.get();
			break;
		case HttpMethod.HEAD:
			response = builder.head();
			break;
		case HttpMethod.OPTIONS:
			response = builder.options();
			break;
		case HttpMethod.POST:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.post(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		case HttpMethod.PUT:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.put(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		default:
			throw new Exception("Invalid method \"" + method + "\"");
		}
		boolean success = false;
		int status = response.getStatus();
		Log.debug(LOGGER, uri.toASCIIString() + " status = " + status);
		if (successStatuses != null && successStatuses.length != 0) {
			for (int successStatus : successStatuses) {
				if (successStatus == status) {
					success = true;
					break;
				}
			}
		} else {
			success = (status == HttpStatus.SC_OK);
		}
		if (!success) {
			String body = response.readEntity(String.class);
			throw new Exception("HTTP Resposne: " + status + ", message: " + body);
		}
		return response;
	}
	
	public static <T> List<T> invokeRestCore(
			Class<T> templateClass,
			Config config, 
			String scheme,
			String domain, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			int... successStatuses) throws Exception {
		List<T> result = new ArrayList<>();
		Client client = ClientBuilder.newClient();
		client.register(JACKSON_JSON_PROVIDER);
		URI uri = new URI(scheme + domain).resolve(path);
		WebTarget target = client.target(uri);
		Log.debug(LOGGER, "uri: " + uri.toASCIIString() + " " + method);
		if (queryParameters != null) {
			for (Map.Entry<String, Object> query : queryParameters.entrySet()) {
				Log.debug(LOGGER, "query: " + query.getKey() + " = " + query.getValue());
				target = target.queryParam(query.getKey(), query.getValue());
			}
		}
		// Check rate
		if (allowance == null) {
			allowance = (float) config.getCloud().getRate();
		}
		long now = System.currentTimeMillis();
		long timePassed = now - lastCheck;
		Log.debug(LOGGER, "Throttle: Time passed: " + timePassed);
		lastCheck = now;
		allowance += timePassed * (config.getCloud().getRate() / config.getCloud().getPeriod());
		Log.debug(LOGGER, "Throttle: Allowance: " + allowance);
		Log.debug(LOGGER, "Throttle: RATE: " + config.getCloud().getRate());
		if (allowance > config.getCloud().getRate()) {
			Log.debug(LOGGER, "Throttle: Allowance > RATE, throttle");
			allowance = config.getCloud().getRate();	// throttle it
		}
		if (allowance < 1) {
			Log.debug(LOGGER, "Throttle: Allowance < 1, sleeping");
			Thread.sleep((long) ((float) config.getCloud().getPeriod()));
		}
		Log.debug(LOGGER, "Throttle: Executing, allowance - 1");
		allowance -= 1;
		Builder builder = target.request();
		if (headers != null) {
			builder = builder.headers(headers);
			for (Map.Entry<String, List<Object>> header : headers.entrySet()) {
				for (Object o : header.getValue()) {
					Log.debug(LOGGER, "header: " + header.getKey() + " = " + o);
				}
			}
		}
		Response response = null;
		switch (method) {
		case HttpMethod.DELETE:
			response = builder.delete();
			break;
		case HttpMethod.GET:
			response = builder.get();
			break;
		case HttpMethod.HEAD:
			response = builder.head();
			break;
		case HttpMethod.OPTIONS:
			response = builder.options();
			break;
		case HttpMethod.POST:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.post(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		case HttpMethod.PUT:
			if (data != null && String.class.isAssignableFrom(data.getClass())) {
				response = builder.post(Entity.entity(data, MediaType.TEXT_PLAIN));
			} else {
				response = builder.put(Entity.entity(data, MediaType.APPLICATION_JSON));
			}
			break;
		default:
			throw new Exception("Invalid method \"" + method + "\"");
		}
		boolean success = false;
		int status = response.getStatus();
		Log.debug(LOGGER, uri.toASCIIString() + " status = " + status);
		if (successStatuses != null && successStatuses.length != 0) {
			for (int successStatus : successStatuses) {
				if (successStatus == status) {
					success = true;
					break;
				}
			}
		} else {
			success = (status == HttpStatus.SC_OK);
		}
		String body = response.readEntity(String.class);
		Log.debug(LOGGER, uri.toASCIIString() + " body = " + body);
		if (success) {
			ObjectReader reader = OM.readerFor(templateClass);
			MappingIterator<T> it = reader.readValues(body);
			while (it.hasNext()) {
				result.add(it.next());
			}
			Log.debug(LOGGER, uri.toASCIIString() + " JSON = " + OM.writeValueAsString(result));
			return result;
		} else {
			throw new Exception("HTTP Resposne: " + status + ", message: " + body);
		}
	}
	
	public static <T> List<T> invokeCloudRest(
			Class<T> templateClass,
			Config config, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			String startAtParameter,
			int... successStatuses) throws Exception {
		return invokeRest(
				templateClass, config, SCHEME, config.getCloud().getDomain(), 
				path, method, headers, queryParameters, data, startAtParameter, successStatuses);
	}
	
	public static <T> List<T> invokeDCConfluenceRest(
			Class<T> templateClass,
			Config config, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			String startAtParameter,
			int... successStatuses) throws Exception {
		return invokeRest(
				templateClass, config, 
				config.getDcExport().getConfluenceScheme(), config.getDcExport().getConfluenceHost(),
				path, method, headers, queryParameters, data, startAtParameter, successStatuses);
	}
	
	public static <T> List<T> invokeDCJiraRest(
			Class<T> templateClass,
			Config config, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			String startAtParameter,
			int... successStatuses) throws Exception {
		return invokeRest(
				templateClass, config, 
				config.getDcExport().getJiraScheme(), config.getDcExport().getJiraHost(),
				path, method, headers, queryParameters, data, startAtParameter, successStatuses);
	}
	
	// Generic REST API invoke
	public static <T> List<T> invokeRest(
			Class<T> templateClass,
			Config config, 
			String scheme,
			String domain, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			String startAtParameter,
			int... successStatuses) throws Exception {
		List<T> result = new ArrayList<>();
		if (Linked.class.isAssignableFrom(templateClass)) {
			boolean hasNext = false;
			Map<String, Object> nextParameters = new HashMap<>();
			nextParameters.putAll(queryParameters);
			do {
				List<T> items = invokeRestCore(
						templateClass, config, scheme, domain, path, method, 
						headers, nextParameters, data, successStatuses);
				if (items != null) {
					result.addAll(items);
				}
				for (T item : items) {
					Linked linkedItem = (Linked) item;
					Map<String, Object> linkedParameters = linkedItem.getNext();
					if (linkedParameters != null) {
						hasNext = true;
						Log.debug(LOGGER, "More items");
						for (Map.Entry<String, Object> entry : linkedParameters.entrySet()) {
							Log.debug(LOGGER, "entry: " + entry.getKey() + " = " + entry.getValue());
						}
						nextParameters.putAll(linkedParameters);
					} else {
						Log.debug(LOGGER, "No more items");
						hasNext = false;
					}
				}
			} while (hasNext);
		} else if (Paged.class.isAssignableFrom(templateClass)) {
			boolean hasNext = false;
			Map<String, Object> nextParameters = new HashMap<>();
			nextParameters.putAll(queryParameters);
			int startAt = 0;
			do {
				List<T> items = invokeRestCore(
						templateClass, config, scheme, domain, 
						path, method, headers, nextParameters, data);
				if (items != null) {
					result.addAll(items);
				}
				for (T item : items) {
					Paged pagedItem = (Paged) item;
					int total = pagedItem.getPageTotal();
					int size = pagedItem.getPageSize();
					// Note: Some REST API does not return startAt, so keep track of it ourselves
					startAt += size;
					if (total != -1) {
						if (startAt < total) {
							nextParameters.put(startAtParameter, startAt);
							hasNext = true;
							Log.debug(LOGGER, "More items");
						} else {
							hasNext = false;
							Log.debug(LOGGER, "No more items");
						}
					} else {
						// Some REST APIs return incorrect total which is equal to size. 
						// So to be safe, total is set to -1, and one extra call is made
						if (size != 0) {
							nextParameters.put(startAtParameter, startAt);
							hasNext = true;
							Log.debug(LOGGER, "More items");
						} else {
							hasNext = false;
							Log.debug(LOGGER, "No more items");
						}
					}
				}
			} while (hasNext);			
		} else {
			List<T> items = invokeRestCore(
					templateClass, config, scheme, domain,
					path, method, headers, queryParameters, data);
			if (items != null) {
				result.addAll(items);
			}
		}
		return result;
	}
}
